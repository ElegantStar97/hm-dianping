package com.hmdp;

import com.hmdp.entity.Shop;
import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisConstants;
import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import javax.annotation.Resource;
import java.util.concurrent.TimeUnit;

@SpringBootTest
class HmDianPingApplicationTests {

	@Resource
	private CacheClient cacheClient;

	@Resource
	private ShopServiceImpl shopService;

	@Test
	void save2Redis() throws InterruptedException {
		Shop shop = shopService.getById(1L);

		cacheClient.setWithLogicExpire(RedisConstants.CACHE_SHOP_KEY + 1L, shop, 10L, TimeUnit.SECONDS);
	}
	
	@Test
	void leetCodeII003() {
		int n = 5;
		int[] arr = new int[n+1];

		int frequency = 0;
		// 循环
		for (int i = 0; i <= n; i++) {

			int count = 0;
			int j = i;
			while (j != 0) {
				count++;
				// low bit算法
				j = j & (j-1);
				if (j != 0) {
					count++;
				}
			}
			arr[frequency] = count;
			frequency++;
		}

		// 返回
		System.out.println(arr);
	}
}
