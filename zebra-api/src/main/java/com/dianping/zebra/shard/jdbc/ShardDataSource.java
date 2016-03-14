/**
 * Project: ${zebra-client.aid}
 *
 * File Created at 2011-6-7 $Id$
 *
 * Copyright 2010 dianping.com. All rights reserved.
 *
 * This software is the confidential and proprietary information of Dianping
 * Company. ("Confidential Information"). You shall not disclose such
 * Confidential Information and shall use it only in accordance with the terms
 * of the license agreement you entered into with dianping.com.
 */
package com.dianping.zebra.shard.jdbc;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;

import javax.sql.DataSource;

import org.apache.log4j.LogManager;
import org.apache.log4j.Logger;

import com.dianping.zebra.config.ConfigService;
import com.dianping.zebra.config.ConfigServiceFactory;
import com.dianping.zebra.group.jdbc.AbstractDataSource;
import com.dianping.zebra.shard.router.DataSourceRepository;
import com.dianping.zebra.shard.router.RouterFactory;
import com.dianping.zebra.shard.router.LionRouterFactory;
import com.dianping.zebra.shard.router.ShardRouter;
import com.dianping.zebra.util.StringUtils;

/**
 * @author Leo Liang
 * @author hao.zhu
 */
public class ShardDataSource extends AbstractDataSource {
	private static final Logger logger = LogManager.getLogger(ShardDataSource.class);

	private String ruleName;

	private Map<String, DataSource> dataSourcePool;

	private RouterFactory routerFactory;

	private DataSourceRepository dataSourceRepository;

	private ShardRouter router;

	private ConfigService configService;

	private volatile boolean closed = false;

	public void close() throws SQLException {
		if (dataSourceRepository != null) {
			dataSourceRepository.close();
		}

		closed = true;

		logger.info(String.format("ShardDataSource(%s) successfully closed.", ruleName));
	}

	@Override
	public Connection getConnection() throws SQLException {
		return getConnection(null, null);
	}

	@Override
	public Connection getConnection(String username, String password) throws SQLException {
		if (closed) {
			throw new SQLException("Datasource has been closed!");
		}

		ShardConnection connection = new ShardConnection(username, password);
		connection.setRouter(router);
		connection.setDataSourceRepository(dataSourceRepository);

		return connection;
	}

	public ShardRouter getRouter() {
		return router;
	}

	public void init() {
		if (StringUtils.isNotBlank(ruleName)) {
			if (configService == null) {
				configService = ConfigServiceFactory.getConfigService(configManagerType, ruleName);
			}

			if (routerFactory == null) {
				routerFactory = new LionRouterFactory(ruleName);
			}
		} else {
			if (dataSourcePool == null || dataSourcePool.isEmpty()) {
				throw new IllegalArgumentException("dataSourcePool is required.");
			}

			if (routerFactory == null) {
				throw new IllegalArgumentException("routerRuleFile must be set.");
			}
		}

		this.router = routerFactory.getRouter();

		if (dataSourceRepository == null) {
			dataSourceRepository = DataSourceRepository.getInstance();
		}

		if (dataSourcePool != null) {
			dataSourceRepository.init(dataSourcePool);
		} else {
			dataSourceRepository.init(this.router.getRouterRule());
		}

		logger.info(String.format("ShardDataSource(%s) successfully initialized.", ruleName));
	}

	public void setConfigService(ConfigService configService) {
		this.configService = configService;
	}

	public void setConfigType(String configType) {
		this.configManagerType = configType;
	}

	public void setDataSourcePool(Map<String, DataSource> dataSourcePool) {
		this.dataSourcePool = dataSourcePool;
	}

	public void setDataSourceRepository(DataSourceRepository dataSourceRepository) {
		this.dataSourceRepository = dataSourceRepository;
	}

	public void setRouterFactory(RouterFactory routerFactory) {
		this.routerFactory = routerFactory;
	}

	public void setRuleName(String ruleName) {
		this.ruleName = ruleName;
	}
}
