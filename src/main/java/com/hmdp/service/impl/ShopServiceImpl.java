package com.hmdp.service.impl;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisData;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 闫博元
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

	@Resource
	private StringRedisTemplate stringRedisTemplate;

	/**
	 * 根据商铺id查询商铺信息
	 *
	 * @param id 商铺id（主键）
	 * @return Result
	 */
	@Override
	public Result queryById(Long id) {
 		// 缓存穿透
		// Shop shop = queryWithPassThrough(id);

		// 设置互斥锁 - 解决缓存击穿
		// Shop shop = queryWithPassMutex(id);

		// 设计逻辑过期 - 解决缓存击穿
		Shop shop = queryWithLogicalExpire(id);

		if (null == shop) {
			return Result.fail("店铺不存在！");
		}

		// 返回
		return Result.ok(shop);
	}

	// 线程池（）
	private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

	/**
	 * 解决缓存击穿 - 设置逻辑过期时间
	 *
	 * @param id 店铺id
	 * @return Shop
	 */
	public Shop queryWithLogicalExpire(Long id) {
		String key = CACHE_SHOP_KEY + id;
		// 1.从 redis 查询商铺缓存
		String shopJson = stringRedisTemplate.opsForValue().get(key);

		// 2.商铺信息不存在于 redis 中
		if (StringUtils.isBlank(shopJson)) {
			// 热点数据的key值未设置过期时间，理论上不可能不命中，既然未命中，直接返回null值
			return null;
		}

		// 3.命中
		RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
		Shop shop = JSONUtil.toBean((JSONObject) redisData.getData(), Shop.class);
		LocalDateTime expireTime = redisData.getExpireTime();

		// 4.判断是否过期
		if (expireTime.isAfter(LocalDateTime.now())) {
			// 未过期，返回店铺信息
			return shop;
		}

		// 5.逻辑过期，缓存重建
		String lockKey = LOCK_SHOP_KEY + id;
		boolean isLock = tryLock(lockKey);
		if (isLock) {
			// 获取互斥锁成功，开启独立线程重现缓存（线程池）
			CACHE_REBUILD_EXECUTOR.submit(() -> {
				try {
					// 重建缓存
					this.saveShop2Redis(id, 20L);
				} catch (Exception e) {
					throw new RuntimeException(e);
				} finally {
					// 释放锁
					unlock(lockKey);
				}
			});
		}

		// 获取锁失败，返回旧的店铺信息（凑合用）
		return shop;
	}

	/**
	 * 解决缓存击穿 - 设置互斥锁
	 *
	 * @param id 店铺id
	 * @return Shop
	 */
	public Shop queryWithPassMutex(Long id) {
		String key = CACHE_SHOP_KEY + id;
		// 1.从 redis 查询商铺缓存
		String shopJson = stringRedisTemplate.opsForValue().get(key);

		// 2.商铺信息存在于 redis 中
		if (StringUtils.isNotBlank(shopJson)) {
			return JSONUtil.toBean(shopJson, Shop.class);
		}
		// 3.命中的值是”“，这里返回的缓存穿透的结果，空值
		if ("".equals(shopJson)) {
			return null;
		}

		// 4.不存在于 redis 中，未命中实现缓存重建
		// 4.1 获取互斥锁
		String lockKey = LOCK_SHOP_KEY + id;
		Shop shop = null;
		boolean isLock = tryLock(lockKey);
		try {
			if (!isLock) {
				// 线程获取锁失败，休眠后重试
				Thread.sleep(50);
				return queryWithPassMutex(id);
			}
			// 获取锁成功
			shop = getById(id);
			// 模拟延迟
			Thread.sleep(200);
			if (shop == null) {
				stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
				return null;
			}

			// 存在，缓存到 redis 中，设置缓存超时时间TTL
			stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
		} catch (InterruptedException e) {
			throw new RuntimeException(e);
		} finally {
			// 释放互斥锁
			unlock(lockKey);
		}
		// 返回
		return shop;
	}

	/**
	 * 解决缓存穿透
	 *
	 * @param id 店铺id
	 * @return Shop
	 */
	public Shop queryWithPassThrough(Long id) {
		String key = CACHE_SHOP_KEY + id;
		// 1.从 redis 查询商铺缓存
		String shopJson = stringRedisTemplate.opsForValue().get(key);

		// 2.商铺信息存在于 redis 中
		if (StringUtils.isNotBlank(shopJson)) {
			return JSONUtil.toBean(shopJson, Shop.class);
		}
		// 3.命中的值是”“，这里返回的缓存穿透的结果，空值
		if ("".equals(shopJson)) {
			return null;
		}

		// 4. 不存在于 redis 中，校验商铺信息是否存在
		Shop shop = getById(id);
		if (shop == null) {
			stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
			return null;
		}

		// 存在，缓存到 redis 中，设置缓存超时时间TTL
		stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
		// 返回
		return shop;
	}

	/**
	 * 获取锁
	 *
	 * @param key
	 * @return boolean
	 */
	private boolean tryLock(String key) {
		Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
		return BooleanUtils.isTrue(flag);
	}

	/**
	 * 释放锁
	 *
	 * @param key
	 */
	private void unlock(String key) {
		stringRedisTemplate.delete(key);
	}

	/**
	 * 存储商铺详情2Redis并添加逻辑过期时间
	 *
	 * @param id			商铺id
	 * @param expireSeconds 逻辑过期时间
	 */
	public void saveShop2Redis(Long id, Long expireSeconds) throws InterruptedException {
		// 1.查询店铺数据
		Shop shop = getById(id);
		// 模拟延迟
		Thread.sleep(200);
		// 2.封装逻辑过期时间
		RedisData redisData = new RedisData();
		redisData.setData(shop);
		redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
		// 3.写入Redis
		stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(redisData));
	}

	/**
	 * 更新商铺详情
	 *
	 * @param shop 商铺详情
	 * @return Result
	 */
	@Override
	@Transactional
	public Result update(Shop shop) {
		if (null == shop.getId()) {
			return Result.fail("店铺id为空，请检查!");
		}
		String key = CACHE_SHOP_KEY + shop.getId();
		// 1.更新数据库
		updateById(shop);
		// 2.删除缓存
		stringRedisTemplate.delete(key);
		return Result.ok();
	}
}
