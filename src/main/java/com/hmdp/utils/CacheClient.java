/**
 * Copyright (C) 2020-2023, Glodon Digital Supplier & Purchaser BU.
 * <p>
 * All Rights Reserved.
 */
package com.hmdp.utils;

import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
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
	 *
	 *
	 * @param key
	 * @param value
	 * @param time
	 * @param unit
	 */
	public void set(String key, Object value, Long time, TimeUnit unit) {
		stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
	}

	/**
	 *
	 *
	 * @param key
	 * @param value
	 * @param time
	 * @param unit
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
	 * @param id 店铺id
	 * @return Shop
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
}
