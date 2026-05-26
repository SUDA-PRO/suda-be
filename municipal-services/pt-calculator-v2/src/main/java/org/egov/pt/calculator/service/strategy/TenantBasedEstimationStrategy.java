package org.egov.pt.calculator.service.strategy;

import java.util.Map;

import org.egov.common.contract.request.RequestInfo;
import org.egov.pt.calculator.web.models.Calculation;
import org.egov.pt.calculator.web.models.CalculationCriteria;

/**
 * TenantBasedEstimationStrategy - Strategy interface for tenant-specific tax estimation
 * Implements Strategy design pattern to support multiple estimation implementations
 *
 * Each tenant (state.city) can have its own estimation logic by implementing this interface
 * Examples: CgJagdalpurEstimationStrategy, CgBhilaiEstimationStrategy, DefaultEstimationStrategy
 *
 * @author PT Calculator Service
 * @version 1.0
 */
public interface TenantBasedEstimationStrategy {

	/**
	 * Calculates tax based on tenant-specific rules
	 *
	 * @param criteria the calculation criteria containing property and financial details
	 * @param requestInfo the request information
	 * @param masterMap the master data map containing MDMS data
	 * @return Calculation object with calculated tax and other details
	 * @throws Exception if calculation fails
	 */
	Calculation calculateTax(CalculationCriteria criteria, RequestInfo requestInfo, Map<String, Object> masterMap) throws Exception;

	/**
	 * Returns the strategy name/identifier
	 * Used for logging and debugging purposes
	 *
	 * @return strategy name
	 */
	String getStrategyName();

	/**
	 * Indicates if this strategy is the default implementation
	 *
	 * @return true if this is the default strategy
	 */
	boolean isDefault();
}

