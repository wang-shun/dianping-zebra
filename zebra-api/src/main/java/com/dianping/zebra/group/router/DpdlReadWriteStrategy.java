package com.dianping.zebra.group.router;

import java.lang.reflect.Method;

import org.apache.log4j.LogManager;
import org.apache.log4j.Logger;

import com.dianping.zebra.group.config.datasource.entity.GroupDataSourceConfig;

public class DpdlReadWriteStrategy implements ReadWriteStrategy {
	private static final Logger logger = LogManager.getLogger(DpdlReadWriteStrategy.class);

	private static Method getContextMethod = null;
	
	private static Method setContextMethod = null;

	private static Method isAuthenticatedMethod = null;

	private static Method setAuthenticatedMethod = null;
	
	private GroupDataSourceConfig config;

	static {
		try {
			Class<?> contextHolderClass = Class.forName("com.dianping.avatar.tracker.ExecutionContextHolder");
			Class<?> contextClass = Class.forName("com.dianping.avatar.tracker.TrackerContext");

			getContextMethod = contextHolderClass.getDeclaredMethod("getTrackerContext", new Class[] {});
			setContextMethod = contextHolderClass.getDeclaredMethod("setTrackerContext",new Class[]{contextClass.getClass()});
			isAuthenticatedMethod = contextClass.getDeclaredMethod("isAuthenticated", new Class[] {});
			setAuthenticatedMethod = contextClass.getDeclaredMethod("setAuthenticated", new Class[] { boolean.class });

			getContextMethod.setAccessible(true);
			isAuthenticatedMethod.setAccessible(true);
			setAuthenticatedMethod.setAccessible(true);
		} catch (Throwable ignore) {
		}
	}

	@Override
	public boolean shouldReadFromMaster() {
		if (config != null && config.getForceWriteOnLogin()) {
			try {
				Object context = getContextMethod.invoke(null);
				
				if(context == null) {
					Class<?> contextClass = Class.forName("com.dianping.avatar.tracker.TrackerContext");
					setContextMethod.invoke(null, contextClass.newInstance());
					context = getContextMethod.invoke(null);
					setAuthenticatedMethod.invoke(context, true);
				}
				
				return (Boolean) isAuthenticatedMethod.invoke(context);
			} catch (Exception error) {
				logger.error(error.getMessage(), error);
			}
		}

		return false;
	}

	protected static void setReadFromMaster() {
		try {
			Object context = getContextMethod.invoke(null);
			
//			if (context != null) {setAuthenticatedMethod.invoke(context, true);}setAuthenticatedMethod.invoke(context, true);
			if(context == null) {
				Class<?> contextClass = Class.forName("com.dianping.avatar.tracker.TrackerContext");
				setContextMethod.invoke(null, contextClass.newInstance());
				context = getContextMethod.invoke(null);
			}
			
			setAuthenticatedMethod.invoke(context, true);
		} catch (Exception error) {
			logger.error(error.getMessage(), error);
		}
	}

	@Override
	public void setGroupDataSourceConfig(GroupDataSourceConfig config) {
		this.config = config;
	}
}
