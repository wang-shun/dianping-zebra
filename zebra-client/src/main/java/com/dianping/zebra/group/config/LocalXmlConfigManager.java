package com.dianping.zebra.group.config;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xml.sax.SAXException;

import com.dianping.zebra.group.config.entity.DatasourceConfig;
import com.dianping.zebra.group.config.entity.DatasourcesConfigs;
import com.dianping.zebra.group.config.transform.DefaultSaxParser;
import com.dianping.zebra.group.exception.ConfigException;

public class LocalXmlConfigManager implements ConfigManager, Runnable {

	private static final Logger logger = LoggerFactory.getLogger(LocalXmlConfigManager.class);

	private AtomicReference<Map<String, DatasourceConfig>> configCache = new AtomicReference<Map<String, DatasourceConfig>>(
	      new HashMap<String, DatasourceConfig>());

	private List<ConfigChangeListener> listeners = new CopyOnWriteArrayList<ConfigChangeListener>();

	private AtomicLong lastModifiedTime = new AtomicLong(-1);

	private File configFile;

	public LocalXmlConfigManager(String xmlPath) {
		URL xmlUrl = getClass().getClassLoader().getResource(xmlPath);
		if (xmlUrl != null) {
			this.configFile = FileUtils.toFile(xmlUrl);
		} else {
			throw new ConfigException(String.format("config file[%s] doesn't exists.", xmlPath));
		}
	}

	@Override
	public void addListerner(ConfigChangeListener listener) {
		listeners.add(listener);
	}

	@Override
	public Map<String, DatasourceConfig> getDatasourceConfigs() {
		return configCache.get();
	}

	private long getMofieiedTime() {
		if (configFile.exists()) {
			return configFile.lastModified();
		} else {
			logger.warn(String.format("config file[%s] doesn't exists.", configFile));
		}

		return -1;
	}

	public void init() {
		try {
			Map<String, DatasourceConfig> newConfig = load();

			if (newConfig == null) {
				throw new ConfigException(String.format("config file[%s] doesn't exists.", configFile));
			}

			configCache.set(newConfig);
			lastModifiedTime.set(getMofieiedTime());

			Thread fileCheckerThread = new Thread(this);

			fileCheckerThread.setDaemon(true);
			fileCheckerThread.setName("Thread-" + LocalXmlConfigManager.class.getSimpleName());
			fileCheckerThread.start();

			logger.info("Successfully initialize LocalXmlConfigManager.");
		} catch (Throwable e) {
			throw new ConfigException(String.format("fail to initialize group datasources with config file[%s].",
			      configFile), e);
		}
	}

	private Map<String, DatasourceConfig> load() throws SAXException, IOException {
		DatasourcesConfigs configs = DefaultSaxParser.parse(FileUtils.readFileToString(configFile));
		if (configs != null) {
			return configs.getDatasourceConfigs();
		} else {
			return null;
		}
	}

	@Override
	public void run() {
		while (!Thread.currentThread().isInterrupted()) {
			try {
				long newModifiedTime = getMofieiedTime();
				if (newModifiedTime > lastModifiedTime.get()) {
					lastModifiedTime.set(newModifiedTime);
					Map<String, DatasourceConfig> newConfig = load();

					if (newConfig != null && !configCache.get().toString().equals(newConfig.toString())) {
						notifyListeners();
					}
				}
			} catch (Throwable throwable) {
				if (logger.isDebugEnabled()) {
					logger.debug(String.format("fail to reload the datasource config[%s]", configFile), throwable);
				}
			}

			try {
				TimeUnit.SECONDS.sleep(10);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				// ignore it
			}
		}
	}

	private void notifyListeners() {
		for (ConfigChangeListener listener : listeners) {
			ConfigChangeEvent event = new ConfigChangeEvent(configCache.get());
			try {
				listener.onChange(event);
			} catch (Throwable e) {
				logger.error(String.format("error to notify the listener %s", listener.getName()), e);
			}
		}
	}
}