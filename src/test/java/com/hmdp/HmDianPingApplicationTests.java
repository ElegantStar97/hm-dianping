package com.hmdp;

import com.hmdp.service.impl.ShopServiceImpl;
import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import javax.annotation.Resource;

@SpringBootTest
class HmDianPingApplicationTests {

	@Resource
	private ShopServiceImpl shopService;

	@Test
	void save2Redis() throws InterruptedException {
		shopService.saveShop2Redis(1L, 10L);
	}
}
