package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;
import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

	@Resource
	private StringRedisTemplate stringRedisTemplate;

	/**
	 * 生成验证码
	 *
	 * @param phone		用户手机号
	 * @param session	session
	 * @return Result
	 */
	@Override
	public Result sendCode(String phone, HttpSession session) {
		// 1.校验手机号
		if (RegexUtils.isPhoneInvalid(phone)) {
			// 2.如果不符合，返回错误信息
			return Result.fail("手机号格式错误");
		}
		// 3.符合，生成验证码
		String code = RandomUtil.randomNumbers(6);
		// 4.(1)存储验证码Redis (2)设置验证码key的有效期
		// set key value ex 120
		stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY + phone, code, LOGIN_CODE_TTL, TimeUnit.MINUTES);
		// 5.发送验证码
		log.debug("发送短信验证码成功，验证码：{}", code);
		// 返回ok
		return Result.ok();
	}

	/**
	 * 用户登录
	 *
	 * @param loginForm 前端发送登录信息
	 * @param session   session
	 * @return Result
	 */
	@Override
	public Result login(LoginFormDTO loginForm, HttpSession session) {
		// 1.校验手机号
		String phone = loginForm.getPhone();
		if (RegexUtils.isPhoneInvalid(phone)) {
			return Result.fail("手机号格式错误");
		}

		// 2.从 redis 中获取验证码并校验
		String cacheCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);
		String code = loginForm.getCode();
		if (null == cacheCode || !cacheCode.equals(code)) {
			// 前端传递验证码和后台生成验证码不一致
			return Result.fail("验证码错误");
		}

		// 3.一致，根据手机号查询用户是否存在
		User user = query().eq("phone", phone).one();

		// 4.判断用户是否存在
		if (null == user) {
			// 不存在，创建新用户并保存
			user = createUserWithPhone(phone);
		}

		// 5.保存用户信息到 redis 中
		// 5.1 随机生成 token，作为登陆令牌
		String token = UUID.randomUUID().toString(true);

		// 5.2 将 User 对象转为 Hash 存储 <String, String> User的id是Long类型，不转换会报错 - ClassCastException
		UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
		Map<String, Object> userMap = BeanUtil.beanToMap(userDTO, new HashMap<>(),
				CopyOptions.create()
						.setIgnoreNullValue(true)
						.setFieldValueEditor((fieldName, fieldValue) -> fieldValue.toString()));

		// 5.3 存储
		String tokenKey = LOGIN_USER_KEY + token;
		stringRedisTemplate.opsForHash().putAll(tokenKey, userMap);

		// 5.4 设置 token 有效期
		stringRedisTemplate.expire(tokenKey, LOGIN_USER_TTL, TimeUnit.MINUTES);

		// 6. 返回 token
		return Result.ok(token);
	}

	private User createUserWithPhone(String phone) {
		// 创建用户
		User user = User.builder().phone(phone).nickName(USER_NICK_NAME_PREFIX + RandomUtil.randomNumbers(10)).build();
		// 保存
		save(user);
		return user;
	}
}
