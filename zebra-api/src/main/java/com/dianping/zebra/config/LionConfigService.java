package com.dianping.zebra.config;

import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.apache.logging.log4j.Logger;

import com.dianping.lion.EnvZooKeeperConfig;
import com.dianping.lion.client.ConfigCache;
import com.dianping.lion.client.ConfigChange;
import com.dianping.lion.client.LionException;
import com.dianping.zebra.Constants;
import com.dianping.zebra.exception.ZebraConfigException;
import com.dianping.zebra.group.config.AdvancedPropertyChangeEvent;
import com.dianping.zebra.log.LoggerLoader;

public class LionConfigService implements ConfigService {
	
	private static final Logger logger = LoggerLoader.getLogger(LionConfigService.class);
	
	private static LionConfigService configService;

	private List<PropertyChangeListener> listeners = new CopyOnWriteArrayList<PropertyChangeListener>();

	private ConfigChange configChange;

	private LionConfigService() {
	}

	public static LionConfigService getInstance() {
		if (configService == null) {
			synchronized (LionConfigService.class) {
				if (configService == null) {
					configService = new LionConfigService();
					configService.init();
				}
			}
		}

		return configService;
	}

	@Override
	public String getProperty(String key) {
		try {
			String value = ConfigCache.getInstance(EnvZooKeeperConfig.getZKAddress()).getProperty(key);
			return value == null ? null : value.trim();
		} catch (LionException e) {
			return null;
		}
	}

	@Override
	public void addPropertyChangeListener(PropertyChangeListener listener) {
		listeners.add(listener);
	}

	@Override
	public void removePropertyChangeListener(PropertyChangeListener listener) {
		listeners.remove(listener);
	}

	@Override
	public void init() {
		try {
			configChange = new ConfigChange() {
				@Override
				public void onChange(String key, String value) {
					if (key.startsWith(Constants.DEFAULT_DATASOURCE_SINGLE_PRFIX)
					      || key.startsWith(Constants.DEFAULT_DATASOURCE_GROUP_PRFIX)
					      || key.startsWith(Constants.DEFAULT_DATASOURCE_ZEBRA_SQL_BLACKLIST_PRFIX)
					      || key.startsWith(Constants.DEFAULT_DATASOURCE_ZEBRA_PRFIX)
					      || key.startsWith(Constants.DEFAULT_SHARDING_PRFIX)) {

						logger.info(String.format("Receive lion change notification. Key[%s], Value[%s]", key, value));

						PropertyChangeEvent event = new AdvancedPropertyChangeEvent(this, key, null, value);
						for (PropertyChangeListener listener : listeners) {
							listener.propertyChange(event);
						}
					}
				}
			};

			ConfigCache.getInstance(EnvZooKeeperConfig.getZKAddress()).addChange(configChange);
		} catch (LionException e) {
			logger.error("fail to initilize Remote Config Manager for DAL", e);
			throw new ZebraConfigException(e);
		}
	}

	@Override
	public void destroy() {
		if (configChange != null) {
			try {
				ConfigCache.getInstance(EnvZooKeeperConfig.getZKAddress()).removeChange(configChange);
			} catch (LionException e) {
				logger.warn("fail to destroy Remote Config Manager for DAL", e);
			} catch (Throwable e) {
				logger.warn("Please Update lion-client version up to 2.4.8", e);
			}
		}
	}
}
