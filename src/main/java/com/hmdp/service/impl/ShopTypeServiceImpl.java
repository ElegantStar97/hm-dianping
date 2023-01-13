package com.hmdp.service.impl;

import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.apache.commons.lang3.StringUtils;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

import java.util.List;


/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

	@Resource
	private StringRedisTemplate stringRedisTemplate;

	/**
	 * 使用 redis 缓存店铺类型信息
	 *
	 * @return
	 */
	@Override
	public Result queryTypeLists() {
		String key = "shopType";
		String shopTypeJson = stringRedisTemplate.opsForValue().get(key);
		// 缓存命中，直接返回数据给前端
		if (StringUtils.isNotBlank(shopTypeJson)) {
			List<ShopType> shopTypes = JSONUtil.toList(shopTypeJson, ShopType.class);
			return Result.ok(shopTypes);
		}
		// 缓存未命中
		List<ShopType> shopTypes = query().orderByAsc("sort").list();
		// 校验
		if (null == shopTypes) {
			Result.fail("查询店铺类型数据不存在!");
		}
		stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shopTypes));
		return Result.ok(shopTypes);
	}
}
