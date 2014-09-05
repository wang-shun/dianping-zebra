package com.dianping.zebra.monitor.filter;

import com.dianping.cat.Cat;
import com.dianping.cat.message.Message;
import com.dianping.cat.message.Transaction;
import com.dianping.cat.status.StatusExtensionRegister;
import com.dianping.zebra.group.filter.AbstractJdbcFilter;
import com.dianping.zebra.group.filter.JdbcMetaData;
import com.dianping.zebra.group.monitor.GroupDataSourceMBean;
import com.dianping.zebra.monitor.monitor.GroupDataSourceMonitor;

/**
 * Created by Dozer on 9/5/14.
 */
public class CatFilter extends AbstractJdbcFilter {
	private static String DAL_CAT_TYPE = "DAL";

	private ThreadLocal<Transaction> refreshGroupDataSourceTransaction = null;

	@Override public void getGroupConnectionAfter(JdbcMetaData metaData) {

	}

	@Override public void getGroupConnectionBefore(JdbcMetaData metaData) {

	}

	@Override public void getGroupConnectionError(JdbcMetaData metaData) {

	}

	@Override public void getGroupConnectionSuccess(JdbcMetaData metaData) {

	}

	@Override public void initGroupDataSourceAfter(JdbcMetaData metaData) {
		if (metaData.getDataSource() instanceof GroupDataSourceMBean) {
			StatusExtensionRegister.getInstance()
					.register(new GroupDataSourceMonitor((GroupDataSourceMBean) metaData.getDataSource()));
		}
	}

	@Override public void initGroupDataSourceBefore(JdbcMetaData metaData) {

	}

	@Override public void initGroupDataSourceError(JdbcMetaData metaData) {

	}

	@Override public void initGroupDataSourceSuccess(JdbcMetaData metaData) {
	}

	private ThreadLocal<Transaction> newTransaction(String type, String name) {
		ThreadLocal<Transaction> temp = new ThreadLocal<Transaction>();
		temp.set(Cat.newTransaction(type, name));
		return temp;
	}

	@Override public void refreshGroupDataSourceAfter(JdbcMetaData metaData, String propertiesName) {

	}

	@Override public void refreshGroupDataSourceBefore(JdbcMetaData metaData, String propertiesName) {
		refreshGroupDataSourceTransaction = newTransaction(DAL_CAT_TYPE, "DataSource.Refresh-" + metaData.getJdbcRef());
		Cat.logEvent("DAL.Refresh.Property", propertiesName);
	}

	@Override public void refreshGroupDataSourceError(JdbcMetaData metaData, String propertiesName, Exception exp) {
		if (refreshGroupDataSourceTransaction != null && refreshGroupDataSourceTransaction.get() != null) {
			refreshGroupDataSourceTransaction.get().setStatus(exp);
		}
	}

	@Override public void refreshGroupDataSourceSuccess(JdbcMetaData metaData, String propertiesName) {
		if (refreshGroupDataSourceTransaction != null && refreshGroupDataSourceTransaction.get() != null) {
			refreshGroupDataSourceTransaction.get().setStatus(Message.SUCCESS);
		}
	}

}
