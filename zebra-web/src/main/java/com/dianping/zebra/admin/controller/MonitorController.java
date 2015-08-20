package com.dianping.zebra.admin.controller;

import java.io.IOException;
import java.lang.reflect.Type;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.client.RestTemplate;

import com.dianping.cat.Cat;
import com.dianping.zebra.biz.dao.MonitorHistoryMapper;
import com.dianping.zebra.biz.dto.InstanceStatusDto;
import com.dianping.zebra.biz.dto.MonitorDto;
import com.dianping.zebra.biz.service.LionService;
import com.dianping.zebra.group.config.DataSourceConfigManager;
import com.dianping.zebra.group.config.DataSourceConfigManagerFactory;
import com.dianping.zebra.group.config.datasource.entity.DataSourceConfig;
import com.dianping.zebra.group.config.datasource.entity.GroupDataSourceConfig;
import com.dianping.zebra.util.StringUtils;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

@Controller
@RequestMapping(value = "/monitor")
public class MonitorController extends BasicController {

	private static final String LION_KEY = "zebra.monitorservice.jdbcreflist";

	private final String USER_NAME = "zebra.monitorservice.jdbc.username";

	private final String USER_PASSWD = "zebra.monitorservice.jdbc.password";

	@Autowired
	private LionService lionService;

	@Autowired
	private MonitorHistoryMapper monitorHistoryDao;

	@Autowired
	private RestTemplate restClient;

	private Gson gson = new Gson();

	private Type type = new TypeToken<Map<String, Set<String>>>() {
	}.getType();

	private Type type1 = new TypeToken<Map<String, InstanceStatusDto>>() {
	}.getType();

	@RequestMapping(value = "/getTickedList", method = RequestMethod.GET)
	@ResponseBody
	public Set<String> getTickedList(String ip) throws Exception {
		if (StringUtils.isBlank(ip)) {
			return null;
		}

		Map<String, Set<String>> ipWithJdbcRef = getIpWithJdbcRef();

		Set<String> jdbcRefSet = new HashSet<String>();

		jdbcRefSet = ipWithJdbcRef.get(ip);

		return jdbcRefSet;
	}

	private String findLowLoadMachine(Map<String, Set<String>> ipWithJdbcRef) {
		String bestIp = null;
		int size = Integer.MAX_VALUE;

		for (Entry<String, Set<String>> entry : ipWithJdbcRef.entrySet()) {
			Set<String> set = entry.getValue();

			if (set == null) {
				size = 0;
				bestIp = entry.getKey();
			} else if (set.size() < size) {
				size = set.size();
				bestIp = entry.getKey();
			}
		}

		return bestIp;
	}

	@RequestMapping(value = "/addJdbcRef", method = RequestMethod.GET)
	@ResponseBody
	public Object addJdbcRef(String jdbcRefs) {
		if (StringUtils.isBlank(jdbcRefs)) {
			return new MonitorDto(-1, "null pointer");
		}

		String[] jdbcRefSplits = jdbcRefs.trim().split(",");

		for (String jdbcRef : jdbcRefSplits) {
			if (isRightJdbcRef(jdbcRef)) {
				Map<String, Set<String>> ipWithJdbcRef = getIpWithJdbcRef();

				boolean isMonitored = false;

				// 检查是否有重复
				for (Entry<String, Set<String>> entry : ipWithJdbcRef.entrySet()) {
					Set<String> monitoredJdbcRef = entry.getValue();

					if (monitoredJdbcRef.contains(jdbcRef)) {
						isMonitored = true;
					}
				}

				if (isMonitored) {
					continue;
				}

				if (!testConnection(jdbcRef)) {
					return new MonitorDto(1,"jdbcRef:"+jdbcRef+" 无法连接到数据库");
				}

				String ip = findLowLoadMachine(ipWithJdbcRef);
				Set<String> jdbcRefSet = ipWithJdbcRef.get(ip);

				jdbcRefSet.add(jdbcRef);

				String json = gson.toJson(ipWithJdbcRef);
				lionService.setConfig(lionService.getEnv(), LION_KEY, json);
			}
		}

		return new MonitorDto(0,"OK");
	}

	public boolean testConnection(String jdbcRef) {
		String uNmae = lionService.getConfigFromZk(USER_NAME);
		String uPasswd = lionService.getConfigFromZk(USER_PASSWD);

		DataSourceConfigManager dsManager = DataSourceConfigManagerFactory.getConfigManager("remote", jdbcRef);
		GroupDataSourceConfig groupDSConf = dsManager.getGroupDataSourceConfig();
		dsManager.close();

		Map<String, DataSourceConfig> dsConfMap = groupDSConf.getDataSourceConfigs();

		for (Map.Entry<String, DataSourceConfig> entry : dsConfMap.entrySet()) {
			DataSourceConfig dsConf = entry.getValue();
			if (dsConf.getActive()) {
				continue;
			}

			String driverClass = dsConf.getDriverClass();

			try {
				Class.forName(driverClass);
			} catch (ClassNotFoundException e) {
				Cat.logError(e);
			}

			String url = dsConf.getJdbcUrl();
			int pos = url.indexOf("&socketTimeout");

			if (pos > 0) {
				url = url.substring(0, pos - 1);
			}
			url += "&connectTimeout=1000&socketTimeout=1000";

			Connection con = null;
			Statement stmt = null;

			try {
				con = DriverManager.getConnection(url, uNmae, uPasswd);
				stmt = con.createStatement();
				stmt.executeQuery("SELECT 1");

			} catch (SQLTimeoutException e) {
				return false;
			} catch (SQLException se) {
				return false;
			} finally {
				close(con, stmt);
			}

		}

		return true;
	}

	private void close(Connection con, Statement stmt) {
		if (stmt != null) {
			try {
				stmt.close();
			} catch (SQLException ignore) {
			}
		}
		if (con != null) {
			try {
				con.close();
			} catch (SQLException ingore) {
			}
		}
	}
	
	@RequestMapping(value = "/removeJdbcRef", method = RequestMethod.GET)
	@ResponseBody
	public Object removeJdbcRef(String jdbcRef) {
		if(StringUtils.isNotBlank(jdbcRef)) {
			Map<String, Set<String>> ipWithJdbcRef = getIpWithJdbcRef();

			if (ipWithJdbcRef == null) {
				return new MonitorDto(0,"OK");
			}
			
			for(Map.Entry<String, Set<String>> entry : ipWithJdbcRef.entrySet()) {
				Set<String> jdbcRefSet = entry.getValue();
				if(jdbcRefSet.contains(jdbcRef)) {
					jdbcRefSet.remove(jdbcRef);
				}
			}
			
			String json = gson.toJson(ipWithJdbcRef);

			lionService.setConfig(lionService.getEnv(), LION_KEY, json);
			
			return new MonitorDto(0,"OK");
		}
		return new MonitorDto(-1,"null jdbcRef");
	}

	@RequestMapping(value = "/submit", method = RequestMethod.GET)
	@ResponseBody
	public Object submitJdbcRef(String ip, String jdbcRefs) throws Exception {
		Map<String, Set<String>> ipWithJdbcRef = getIpWithJdbcRef();

		if (ipWithJdbcRef == null) {
			ipWithJdbcRef = new HashMap<String, Set<String>>();
		}

		if (StringUtils.isNotBlank(jdbcRefs)) {
			Set<String> newJdbcRefs = new HashSet<String>();

			if (!jdbcRefs.equalsIgnoreCase("null")) {
				String[] jdbcRefSplits = jdbcRefs.trim().split(",");

				for (String jdbcRef : jdbcRefSplits) {
					boolean isMonitored = false;

					// 在其他IP中已经监控的jdbcRef不会再一次被监控
					for (Entry<String, Set<String>> entry : ipWithJdbcRef.entrySet()) {
						Set<String> monitoredJdbcRef = entry.getValue();

						if (!entry.getKey().equals(ip) && monitoredJdbcRef.contains(jdbcRef)) {
							isMonitored = true;
							break;
						}
					}

					if (!testConnection(jdbcRef)) {
						return new MonitorDto(1,"jdbcRef:"+jdbcRef+" 无法连接到数据库");
					}

					if (!isMonitored) {
						newJdbcRefs.add(jdbcRef);
					}
				}
			}

			ipWithJdbcRef.put(ip, newJdbcRefs);

			String json = gson.toJson(ipWithJdbcRef);

			lionService.setConfig(lionService.getEnv(), LION_KEY, json);
		}

		return new MonitorDto(0,"OK");
	}

	public boolean isRightJdbcRef(String jdbcRef) {
		if (StringUtils.isBlank(jdbcRef)) {
			return false;
		}

		Set<String> jdbcRefSet = getJdbcRefSet();

		return jdbcRefSet.contains(jdbcRef);
	}

	private Map<String, Set<String>> getIpWithJdbcRef() {
		String config = lionService.getConfigFromZk(LION_KEY);
		if (config != null) {
			return gson.fromJson(config, type);
		} else {
			return null;
		}
	}

	@RequestMapping(value = "/getStatus", method = RequestMethod.GET)
	@ResponseBody
	public Object getStatus(String ip) throws Exception {
		Map<String, InstanceStatusDto> result = new HashMap<String, InstanceStatusDto>();

		try {
			String url = String.format("http://%s:8080/a/status", ip);
			String jsonBody = restClient.exchange(url, HttpMethod.GET, null, String.class).getBody();

			if (jsonBody != null) {
				Map<String, InstanceStatusDto> fromJson = gson.fromJson(jsonBody, type1);

				result.putAll(fromJson);
			}
		} catch (Exception e) {
			Cat.logError(e);
		}

		return result;
	}

	private Set<String> getJdbcRefSet() {
		Set<String> jdbcRefSet = new HashSet<String>();
		try {
			HashMap<String, String> dsKV = lionService.getConfigByProject(lionService.getEnv(), "groupds");

			synchronized (jdbcRefSet) {
				for (Entry<String, String> entry : dsKV.entrySet()) {
					String key = entry.getKey();
					String value = entry.getValue();

					if (key != null && value != null) {
						int begin = "groupds.".length();
						int end = key.indexOf(".mapping");
						if (end == -1 || begin == -1) {
							continue;
						}

						jdbcRefSet.add(key.substring(begin, end).toLowerCase());
					}
				}
			}
		} catch (IOException e) {
		}
		return jdbcRefSet;
	}

	@RequestMapping(value = "/getJdbcRefList", method = RequestMethod.GET)
	@ResponseBody
	public Set<String> getJdbcRefList() {
		return getJdbcRefSet();
	}

	@RequestMapping(value = "/servers", method = RequestMethod.GET)
	@ResponseBody
	public Object getServers() throws Exception {
		return getIpWithJdbcRef().keySet();
	}

	@RequestMapping(value = "/history", method = RequestMethod.GET)
	@ResponseBody
	public Object showHistory() throws Exception {
		return monitorHistoryDao.findAllHistory();
	}
}
