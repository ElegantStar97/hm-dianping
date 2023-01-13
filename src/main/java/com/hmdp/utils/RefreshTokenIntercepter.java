/**
 * Copyright (C) 2020-2023, Glodon Digital Supplier & Purchaser BU.
 * <p>
 * All Rights Reserved.
 */
package com.hmdp.utils;

import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.UserDTO;
import org.apache.commons.lang3.StringUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.LOGIN_USER_KEY;
import static com.hmdp.utils.RedisConstants.LOGIN_USER_TTL;

/**
 * 登录校验拦截器
 *
 * @author 闫博元
 * @date 2023-01-04 16:47:42
 */
public class RefreshTokenIntercepter implements HandlerInterceptor {

	// 当前类不是 Spring 管理的，无法进行依赖注入，通过构造函数注入
	private StringRedisTemplate stringRedisTemplate;

	public RefreshTokenIntercepter(StringRedisTemplate stringRedisTemplate) {
		this.stringRedisTemplate = stringRedisTemplate;
	}

	/**
	 * 登录用户信息拦截
	 * 说明：未登录的用户不放行
	 *
	 * @param request	请求
	 * @param response	响应
	 * @param handler	handler
	 * @return
	 * @throws Exception
	 */
	@Override
	public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
		// 1. 获取请求头中的 token
		String token = request.getHeader("authorization");
		if (StringUtils.isBlank(token)) {
			// 直接放行
			return true;
		}

		// 2. 基于 token 获取 redis 中的用户
		String key = LOGIN_USER_KEY + token;
		Map<Object, Object> userMap = stringRedisTemplate.opsForHash().entries(key);

		// 3.判断用户是否存在
		if (userMap.isEmpty()) {
			// 放行
			return true;
		}

		// 4. 将查询到的 Hash 数据转为 UserDTO对象
		UserDTO userDTO = BeanUtil.fillBeanWithMap(userMap, new UserDTO(), false);

		// 5.存在，保存用户信息到 TheadLocal
		UserHolder.saveUser(userDTO);

		// 6. 刷新 token 有效期
		stringRedisTemplate.expire(key, LOGIN_USER_TTL, TimeUnit.MINUTES);

		// 7.放行
		return true;
	}

	@Override
	public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
		// 移除用户，业务执行完毕，销毁对应的用户信息，避免内存泄露（OOM）
		UserHolder.removeUser();
	}
}
