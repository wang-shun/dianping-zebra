package com.dianping.zebra.admin.job.executor;

import com.dianping.cat.Cat;
import com.dianping.puma.api.ConfigurationBuilder;
import com.dianping.puma.api.EventListener;
import com.dianping.puma.api.PumaClient;
import com.dianping.puma.core.constant.SubscribeConstant;
import com.dianping.puma.core.event.ChangedEvent;
import com.dianping.puma.core.event.RowChangedEvent;
import com.dianping.puma.core.util.sql.DMLType;
import com.dianping.zebra.admin.dao.PumaClientSyncTaskMapper;
import com.dianping.zebra.admin.entity.PumaClientSyncTaskEntity;
import com.dianping.zebra.admin.exception.NoRowsAffectedException;
import com.dianping.zebra.admin.util.ColumnInfoWrap;
import com.dianping.zebra.admin.util.SqlGenerator;
import com.dianping.zebra.group.jdbc.GroupDataSource;
import com.dianping.zebra.group.router.RouterType;
import com.dianping.zebra.shard.router.rule.DataSourceBO;
import com.dianping.zebra.shard.router.rule.SimpleDataSourceProvider;
import com.dianping.zebra.shard.router.rule.engine.GroovyRuleEngine;
import com.dianping.zebra.shard.router.rule.engine.RuleEngineEvalContext;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.SQLException;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Dozer @ 6/9/15
 * mail@dozer.cc
 * http://www.dozer.cc
 */
public class PumaClientSyncTaskExecutor implements TaskExecutor {
	private PumaClientSyncTaskMapper pumaClientSyncTaskMapper;

	private final PumaClientSyncTaskEntity task;

	protected GroovyRuleEngine engine;

	protected SimpleDataSourceProvider dataSourceProvider;

	protected PumaClient client;

	protected Thread taskSequenceUploader;

	protected static Map<String, GroupDataSource> dataSources = new ConcurrentHashMap<String, GroupDataSource>();

	public PumaClientSyncTaskExecutor(PumaClientSyncTaskEntity task) {
		this.task = task;
	}

	public synchronized void init() {
		try {
			initRouter();
			initDataSources();
			initPumaClient();
			initSequenceUploader();
		} catch (Exception exp) {
			Cat.logError(exp);
		}
	}

	public synchronized void start() {
		client.start();
		taskSequenceUploader.start();
	}

	public synchronized void pause() {
		client.stop();
		taskSequenceUploader.interrupt();
	}

	public synchronized void stop() {
		if (client != null) {
			client.stop();
		}
		if (taskSequenceUploader != null) {
			taskSequenceUploader.interrupt();
		}
		for (GroupDataSource ds : dataSources.values()) {
			try {
				ds.close();
			} catch (SQLException ignore) {
			}
		}
		dataSources.clear();
	}

	protected void initSequenceUploader() {
		taskSequenceUploader = new Thread(new TaskSequenceUploader());
		taskSequenceUploader.setName("TaskSequenceUploader");
		taskSequenceUploader.setDaemon(true);
	}

	protected void initRouter() {
		this.engine = new GroovyRuleEngine(task.getDbRule());
		this.dataSourceProvider = new SimpleDataSourceProvider(task.getTableName(), task.getDbIndexes(),
			task.getTbSuffix(), task.getTbRule());
	}

	protected void initDataSources() {
		for (Map.Entry<String, Set<String>> entity : dataSourceProvider.getAllDBAndTables().entrySet()) {
			String jdbcRef = entity.getKey();
			if (!dataSources.containsKey(jdbcRef)) {
				GroupDataSource ds = initGroupDataSource(jdbcRef);
				dataSources.put(jdbcRef, ds);
			}
		}
	}

	protected GroupDataSource initGroupDataSource(String jdbcRef) {
		GroupDataSource ds = new GroupDataSource(jdbcRef);
		ds.setRouterType(RouterType.FAIL_OVER.getRouterType());
		ds.setFilter("!cat");
		ds.init();
		return ds;
	}

	protected void initPumaClient() {
		ConfigurationBuilder configBuilder = new ConfigurationBuilder();
		configBuilder.dml(true).ddl(false).transaction(false).target(task.getPumaTaskName());
		String fullName = String.format("%s-%d", "PumaClientSyncTask", task.getId());
		configBuilder.name(fullName);
		configBuilder.tables(task.getPumaDatabase(), task.getPumaTables().split(","));

		this.client = new PumaClient(configBuilder.build());
		this.client.register(new PumaEventListener());
		client.getSeqFileHolder()
			.saveSeq(task.getSequence() == 0 ? SubscribeConstant.SEQ_FROM_LATEST : task.getSequence());
	}

	class TaskSequenceUploader implements Runnable {
		@Override
		public void run() {
			long lastSeq = 0;

			while (true) {
				try {
					Thread.sleep(5 * 1000);
				} catch (InterruptedException e) {
					break;
				}

				if (lastSeq == task.getSequence()) {
					continue;
				}

				try {
					lastSeq = task.getSequence();
					pumaClientSyncTaskMapper.updateSequence(task);
				} catch (Exception e) {
					Cat.logError(e);
				}
			}
		}
	}

	class PumaEventListener implements EventListener {
		protected volatile long tryTimes = 0;

		@Override
		public void onEvent(ChangedEvent event) throws Exception {
			tryTimes++;
			onEventInternal(event);
			task.setSequence(event.getSeq());
			tryTimes = 0;
		}

		protected void onEventInternal(ChangedEvent event) {
			if (!(event instanceof RowChangedEvent)) {
				return;
			}
			RowChangedEvent rowEvent = (RowChangedEvent) event;
			if (rowEvent.isTransactionBegin() || rowEvent.isTransactionCommit()) {
				return;
			}

			convertKey(rowEvent);

			ColumnInfoWrap column = new ColumnInfoWrap(rowEvent);
			Number index = (Number) engine.eval(new RuleEngineEvalContext(column));
			DataSourceBO bo = dataSourceProvider.getDataSource(index.intValue());
			String table = bo.evalTable(column);

			rowEvent.setTable(table);
			rowEvent.setDatabase("");
			String sql = SqlGenerator.parseSql(rowEvent);
			Object[] args = SqlGenerator.parseArgs(rowEvent);

			JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSources.get(bo.getDbIndex()));
			int rows = jdbcTemplate.update(sql, args);
			if (rows == 0 && RowChangedEvent.UPDATE == rowEvent.getActionType()) {
				throw new NoRowsAffectedException();
			}
		}

		protected void convertKey(RowChangedEvent rowEvent) {
			Set<String> needToRemoveKey = new HashSet<String>();
			for (Map.Entry<String, RowChangedEvent.ColumnInfo> info : rowEvent.getColumns().entrySet()) {
				if (info.getValue().isKey() && !task.getPk().equals(info.getKey())) {
					needToRemoveKey.add(info.getKey());
				}
			}
			for (String key : needToRemoveKey) {
				rowEvent.getColumns().remove(key);
			}
			rowEvent.getColumns().get(task.getPk()).setKey(true);
		}

		@Override
		public boolean onException(ChangedEvent event, Exception e) {
			Cat.logError(e);

			RowChangedEvent rowEvent = (RowChangedEvent) event;

			if (e instanceof DuplicateKeyException) {
				rowEvent.setDmlType(DMLType.UPDATE);
				return false;
			} else if (e instanceof NoRowsAffectedException) {
				rowEvent.setDmlType(DMLType.INSERT);
				return false;
			} else {
				//不断重试，随着重试次数增多，sleep 时间增加
				try {
					Thread.sleep(tryTimes);
				} catch (InterruptedException ignore) {
					return true;
				}
				return false;
			}
		}

		@Override
		public void onConnectException(Exception e) {

		}

		@Override
		public void onConnected() {

		}

		@Override
		public void onSkipEvent(ChangedEvent event) {

		}
	}

	public void setPumaClientSyncTaskMapper(PumaClientSyncTaskMapper pumaClientSyncTaskMapper) {
		this.pumaClientSyncTaskMapper = pumaClientSyncTaskMapper;
	}
}
