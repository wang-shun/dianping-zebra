package com.dianping.zebra.group.router;import java.util.Set;/** * 对等DataSource选择器。 *  */public interface GroupDataSourceRouter {	/**	 * 路由策略名称	 */	String getRouterStrategy();	/**	 * 对等DataSource选择器。 在数据完全相同的一组DataSource中选择一个DataSource	 */	GroupDataSourceTarget select(GroupDataSourceRouterInfo routerInfo);	/**	 * 对等DataSource选择器。 在数据完全相同的一组DataSource中选择一个DataSource	 */	GroupDataSourceTarget select(GroupDataSourceRouterInfo routerInfo, Set<GroupDataSourceTarget> excludeTargets);}