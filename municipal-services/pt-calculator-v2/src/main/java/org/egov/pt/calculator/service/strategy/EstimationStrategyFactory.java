package org.egov.pt.calculator.service.strategy;

import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.egov.pt.calculator.service.strategy.tenants.DefaultEstimationStrategy;
import org.egov.pt.calculator.util.EstimationCommonUtil;
import org.egov.tracer.model.CustomException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * EstimationStrategyFactory - Factory for resolving tenant-specific estimation strategies
 *
 * This factory implements the Factory design pattern to dynamically resolve and instantiate
 * the appropriate estimation strategy bean based on tenant code.
 *
 * Strategy Resolution Flow:
 * 1. Extract tenant code from property (e.g., cg.jagdalpur)
 * 2. Generate bean name suffix (e.g., CgJagdalpur)
 * 3. Try to find tenant-specific bean (cgJagdalpurEstimationStrategy)
 * 4. If not found, return default strategy bean
 * 5. Log the resolution for audit trail
 *
 * @author PT Calculator Service
 * @version 1.0
 */
@Component
@Slf4j
public class EstimationStrategyFactory {

	@Autowired
	private ApplicationContext applicationContext;

	@Autowired
	private EstimationCommonUtil estimationCommonUtil;

	@Autowired
	private DefaultEstimationStrategy defaultEstimationStrategy;

	private static final String STRATEGY_BEAN_SUFFIX = "EstimationStrategy";

	/**
	 * Resolves the appropriate estimation strategy bean based on tenant code
	 *
	 * @param tenantId the tenant code (e.g., cg.jagdalpur, cg.bhilai)
	 * @return TenantBasedEstimationStrategy implementation
	 * @throws CustomException if strategy resolution fails
	 */
	public TenantBasedEstimationStrategy resolveStrategy(String tenantId) {
		try {
			log.info("Resolving estimation strategy for tenantId: {}", tenantId);

			// Return default if tenant code is invalid or empty
			if (!estimationCommonUtil.isValidTenantCode(tenantId)) {
				log.warn("Invalid or empty tenant code: {}. Using default strategy", tenantId);
				estimationCommonUtil.logStrategyResolution(tenantId,
					defaultEstimationStrategy.getStrategyName(), true);
				return defaultEstimationStrategy;
			}

			// Generate bean name from tenant code
			String beanNameSuffix = estimationCommonUtil.getBeanNameSuffix(tenantId);
			if (StringUtils.isEmpty(beanNameSuffix)) {
				log.warn("Could not generate bean name suffix from tenant code: {}. Using default strategy", tenantId);
				estimationCommonUtil.logStrategyResolution(tenantId,
					defaultEstimationStrategy.getStrategyName(), true);
				return defaultEstimationStrategy;
			}

			// Construct bean name (lowercase first letter)
			String beanName = lowerCaseFirstLetter(beanNameSuffix) + STRATEGY_BEAN_SUFFIX;
			log.info("Looking for strategy bean: {}", beanName);

			// Try to get tenant-specific strategy bean
			try {
				TenantBasedEstimationStrategy strategy = applicationContext.getBean(beanName, TenantBasedEstimationStrategy.class);
				log.info("Found tenant-specific strategy bean: {} for tenant: {}", beanName, tenantId);
				estimationCommonUtil.logStrategyResolution(tenantId, strategy.getStrategyName(), false);
				return strategy;
			} catch (Exception e) {
				log.info("Tenant-specific strategy bean not found: {}. Using default strategy. Error: {}", beanName, e.getMessage());
				estimationCommonUtil.logStrategyResolution(tenantId,
					defaultEstimationStrategy.getStrategyName(), true);
				return defaultEstimationStrategy;
			}
		} catch (Exception e) {
			log.error("Error resolving estimation strategy for tenant: {}. Falling back to default. Error: {}",
				tenantId, e.getMessage(), e);
			return defaultEstimationStrategy;
		}
	}

	/**
	 * Resolves strategy using a Map containing application context for bean lookup
	 * This method provides alternative resolution mechanism
	 *
	 * @param tenantCode the tenant Id
	 * @param strategyMap map of available strategy beans (beanName -> strategy)
	 * @return TenantBasedEstimationStrategy implementation
	 */
	public TenantBasedEstimationStrategy resolveStrategy(String tenantCode, Map<String, TenantBasedEstimationStrategy> strategyMap) {
		try {
			log.info("Resolving strategy from map for tenant: {}", tenantCode);

			if (!estimationCommonUtil.isValidTenantCode(tenantCode)) {
				log.warn("Invalid tenant code: {}. Using default strategy", tenantCode);
				return defaultEstimationStrategy;
			}

			String beanNameSuffix = estimationCommonUtil.getBeanNameSuffix(tenantCode);
			if (StringUtils.isEmpty(beanNameSuffix)) {
				return defaultEstimationStrategy;
			}

			String beanName = lowerCaseFirstLetter(beanNameSuffix) + STRATEGY_BEAN_SUFFIX;
			TenantBasedEstimationStrategy strategy = strategyMap.get(beanName);

			if (strategy != null) {
				log.info("Found strategy from map: {} for tenant: {}", beanName, tenantCode);
				return strategy;
			} else {
				log.info("Strategy not found in map: {}. Using default strategy", beanName);
				return defaultEstimationStrategy;
			}
		} catch (Exception e) {
			log.error("Error resolving strategy from map for tenant: {}. Error: {}", tenantCode, e.getMessage(), e);
			return defaultEstimationStrategy;
		}
	}

	/**
	 * Converts first letter of string to lowercase
	 * Example: CgJagdalpur → cgJagdalpur
	 *
	 * @param input the input string
	 * @return string with first letter in lowercase
	 */
	private String lowerCaseFirstLetter(String input) {
		if (StringUtils.isEmpty(input)) {
			return input;
		}
		return input.substring(0, 1).toLowerCase() + input.substring(1);
	}

	/**
	 * Checks if a strategy bean exists in the application context
	 *
	 * @param tenantId the tenant code
	 * @return true if tenant-specific strategy bean exists
	 */
	public boolean hasTenantSpecificStrategy(String tenantId) {
		if (!estimationCommonUtil.isValidTenantCode(tenantId)) {
			return false;
		}
		try {
			String beanNameSuffix = estimationCommonUtil.getBeanNameSuffix(tenantId);
			if (StringUtils.isEmpty(beanNameSuffix)) {
				return false;
			}
			String beanName = lowerCaseFirstLetter(beanNameSuffix) + STRATEGY_BEAN_SUFFIX;
			return applicationContext.containsBean(beanName);
		} catch (Exception e) {
			log.warn("Error checking for tenant-specific strategy: {}", e.getMessage());
			return false;
		}
	}
}

