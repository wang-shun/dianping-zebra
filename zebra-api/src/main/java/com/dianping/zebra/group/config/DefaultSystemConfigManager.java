package com.dianping.zebra.group.config;

import com.dianping.zebra.group.Constants;
import com.dianping.zebra.group.config.system.entity.SystemConfig;
import com.dianping.zebra.group.exception.IllegalConfigException;
import com.dianping.zebra.group.util.AppPropertiesUtils;
import com.dianping.zebra.group.util.StringUtils;

import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.util.concurrent.atomic.AtomicReference;

public class DefaultSystemConfigManager extends AbstractConfigManager implements SystemConfigManager {

	private AtomicReference<SystemConfig> systemConfig = new AtomicReference<SystemConfig>();

	public DefaultSystemConfigManager(String resourceId, ConfigService configService) {
		super(resourceId, configService);
	}

	@Override
	public void addListerner(PropertyChangeListener listener) {
		listeners.add(listener);
	}

	private String getKey(String key) {
		return String.format("%s.%s", this.jdbcRef, key);
	}

	private String getGlobalSqlBlackListKey() {
		return String.format("%s.global.id", Constants.DEFAULT_DATASOURCE_ZEBRA_SQL_BLACKLIST_PRFIX);
	}

	private String getAppSqlBlackListKey() {
		String appName = AppPropertiesUtils.getAppName();
		if (appName.equals(Constants.PHOENIX_APP_NO_NAME)) {
			return null;
		}
		return String.format("%s.app.%s.id", Constants.DEFAULT_DATASOURCE_ZEBRA_SQL_BLACKLIST_PRFIX, appName);
	}

	@Override
	public SystemConfig getSystemConfig() {
		return this.systemConfig.get();
	}

	@Override
	public void init() {
		try {
			this.systemConfig.set(initSystemConfig());
		} catch (Exception e) {
			throw new IllegalConfigException(String.format(
			      "Fail to initialize DefaultSystemConfigManager with config file[%s].", this.jdbcRef), e);
		}
	}

	public SystemConfig initSystemConfig() {
		SystemConfig config = new SystemConfig();

		config.setRetryTimes(getProperty(getKey(Constants.ELEMENT_RETRY_TIMES), config.getRetryTimes()));
		config.setGlobalBlackList(getProperty(getGlobalSqlBlackListKey(), config.getGlobalBlackList()));
		
		String appBlackListKey = getAppSqlBlackListKey();
		if (!StringUtils.isBlank(appBlackListKey)) {
			config.setAppBlackList(getProperty(getAppSqlBlackListKey(), config.getAppBlackList()));
		}
		
		return config;
	}

	protected void onPropertyUpdated(PropertyChangeEvent evt) {
		String key = evt.getPropertyName();
		Object newValue = evt.getNewValue();
		String appBlackListKey = getAppSqlBlackListKey();

		synchronized (this.systemConfig) {
			SystemConfig config = this.systemConfig.get();

			if (key.equals(getKey(Constants.ELEMENT_RETRY_TIMES))) {
				config.setRetryTimes(getProperty(getKey(Constants.ELEMENT_RETRY_TIMES), config.getRetryTimes()));
			} else if (key.equals(appBlackListKey)) {
				config.setAppBlackList((String) newValue);
			} else if (key.equals(getGlobalSqlBlackListKey())) {
				config.setGlobalBlackList((String) newValue);
				if (!StringUtils.isBlank(appBlackListKey)) {
					config.setAppBlackList(getProperty(getAppSqlBlackListKey(), config.getAppBlackList()));
				}
			}
		}
	}
}
