/**
 * Copyright (C) 2020-2023, Glodon Digital Supplier & Purchaser BU.
 * <p>
 * All Rights Reserved.
 */
package com.hmdp.config;

import com.hmdp.utils.LoginIntercepter;
import com.hmdp.utils.RefreshTokenIntercepter;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import javax.annotation.Resource;

/**
 * 配置拦截器
 *
 * @author 闫博元
 * @date 2023-01-04 16:56:38
 */
@Configuration
public class MvcConfig implements WebMvcConfigurer {

	@Resource
	private StringRedisTemplate stringRedisTemplate;

	/**
	 * 配置拦截器
	 *
	 * @param registry 拦截器的注册器
	 */
	@Override
	public void addInterceptors(InterceptorRegistry registry) {
		// 登录拦截器
		registry.addInterceptor(new LoginIntercepter())
				.excludePathPatterns(
						"/shop/**",
						"/voucher/**",
						"/shop-type/**",
						"/upload/**",
						"/blog/hot",
						"/user/code",
						"/user/login"
				).order(1);
		// token 刷新拦截器
		// 默认拦截所有请求，不放心加"/**"
		// order 确保拦截器执行顺序。order 值越小，执行优先级越高
		registry.addInterceptor(new RefreshTokenIntercepter(stringRedisTemplate))
				.addPathPatterns("/**").order(0);
	}

}
