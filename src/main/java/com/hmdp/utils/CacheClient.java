/**
 * Copyright (C) 2020-2023, Glodon Digital Supplier & Purchaser BU.
 * <p>
 * All Rights Reserved.
 */
package com.hmdp.utils;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;

/**
 * 封装 Redis 缓存的工具类
 *
 * @author 闫博元
 * @date 2023-01-12 15:02:02
 */
@Slf4j
@Component
public class CacheClient {

	private final StringRedisTemplate stringRedisTemplate;

	public CacheClient(StringRedisTemplate stringRedisTemplate) {
		this.stringRedisTemplate = stringRedisTemplate;
	}

	/**
	 * 将任意Java对象序列化为json并存储在string类型的key中，并且可以设置TTL过期时间
	 *
	 * @param key    键
	 * @param value  值
	 * @param time   过期时间
	 * @param unit	 时间单位
	 */
	public void set(String key, Object value, Long time, TimeUnit unit) {
		stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
	}

	/**
	 * 将任意Java对象序列化为json并存储在string类型的key中，并且可以设置逻辑过期时间，用于处理缓存击穿问题
	 *
	 * @param key    键
	 * @param value  值
	 * @param time   过期时间
	 * @param unit   时间单位
	 */
	public void setWithLogicExpire(String key, Object value, Long time, TimeUnit unit) {
		// 设置逻辑过期
		RedisData redisData = new RedisData();
		redisData.setData(value);
		redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
		// 写入Redis
		stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
	}

	/**
	 * 解决缓存穿透
	 *
	 * @param keyPrefix  缓存key的前缀
	 * @param id		 店铺id
	 * @param type       查询类型：店铺
	 * @param dbFallback 查询数据库函数
	 * @param time		 设置缓存超时时间
	 * @param unit       超时时间单位
	 * @return
	 * @param <R>
	 * @param <ID>
	 */
	public <R, ID> R queryWithPassThrough(
			String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit) {
		String key = keyPrefix + id;
		// 1.从 redis 查询商铺缓存
		String json = stringRedisTemplate.opsForValue().get(key);

		// 2.商铺信息存在于 redis 中
		if (StringUtils.isNotBlank(json)) {
			return JSONUtil.toBean(json, type);
		}
		// 3.命中的值是”“，这里返回的缓存穿透的结果，空值
		if ("".equals(json)) {
			return null;
		}

		// 4. 不存在于 redis 中，校验商铺信息是否存在
		R r = dbFallback.apply(id);
		if (r == null) {
			stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
			return null;
		}

		// 存在，缓存到 redis 中，设置缓存超时时间TTL
		this.set(key, r, time, unit);
		// 返回
		return r;
	}

	// 线程池
	private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

	/**
	 * 解决缓存击穿 - 设置逻辑过期时间
	 *
	 * @param keyPrefix  缓存key的前缀
	 * @param id		 店铺id
	 * @param type		 查询类型：店铺
	 * @param dbFallback 查询数据库函数
	 * @param time       设置缓存逻辑过期时间
	 * @param unit		 过期时间单位
	 * @return
	 * @param <R>
	 * @param <ID>
	 */
	public <R, ID> R queryWithLogicalExpire(
			String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit) {
		String key = keyPrefix + id;
		// 1.从 redis 查询商铺缓存
		String json = stringRedisTemplate.opsForValue().get(key);

		// 2.商铺信息不存在于 redis 中
		if (StringUtils.isBlank(json)) {
			// 热点数据的key值未设置过期时间，理论上不可能不命中，既然未命中，直接返回null值
			return null;
		}

		// 3.命中
		RedisData redisData = JSONUtil.toBean(json, RedisData.class);
		R r = JSONUtil.toBean((JSONObject) redisData.getData(), type);
		LocalDateTime expireTime = redisData.getExpireTime();

		// 4.判断是否过期
		if (expireTime.isAfter(LocalDateTime.now())) {
			// 未过期，返回店铺信息
			return r;
		}

		// 5.逻辑过期，缓存重建
		String lockKey = LOCK_SHOP_KEY + id;
		boolean isLock = tryLock(lockKey);
		if (isLock) {
			// 获取互斥锁成功，开启独立线程重现缓存（线程池）
			CACHE_REBUILD_EXECUTOR.submit(() -> {
				try {
					// 重建缓存
					// 查询数据库
					R r1 = dbFallback.apply(id);
					// 写入缓存（带逻辑过期时间）
					this.setWithLogicExpire(key, r1, time, unit);
				} catch (Exception e) {
					throw new RuntimeException(e);
				} finally {
					// 释放锁
					unlock(lockKey);
				}
			});
		}

		// 获取锁失败，返回旧的店铺信息（凑合用）
		return r;
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

}
