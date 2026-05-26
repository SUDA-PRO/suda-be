package org.egov.pt.calculator.service;

import java.util.Map;

import org.egov.common.contract.request.RequestInfo;
import org.egov.pt.calculator.service.strategy.EstimationStrategyFactory;
import org.egov.pt.calculator.service.strategy.TenantBasedEstimationStrategy;
import org.egov.pt.calculator.util.EstimationCommonUtil;
import org.egov.pt.calculator.web.models.Calculation;
import org.egov.pt.calculator.web.models.CalculationCriteria;
import org.egov.pt.calculator.web.models.property.Property;
import org.egov.tracer.model.CustomException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

/**
 * TenantBasedTaxCalculationService - Wrapper service for tenant-based tax calculation
 *
 * This service acts as a facade that:
 * 1. Extracts tenant code from property
 * 2. Uses EstimationStrategyFactory to resolve appropriate strategy
 * 3. Delegates calculation to the resolved strategy
 * 4. Provides error handling and logging
 *
 * @author PT Calculator Service
 * @version 1.0
 */
@Service
@Slf4j
public class TenantBasedTaxCalculationService {

	@Autowired
	private EstimationStrategyFactory strategyFactory;

	@Autowired
	private EstimationCommonUtil estimationCommonUtil;

	/**
	 * Calculates tax using tenant-specific or default estimation strategy
	 *
	 * @param criteria the calculation criteria
	 * @param requestInfo the request information
	 * @param masterMap the master data map
	 * @return Calculation object with calculated tax
	 * @throws CustomException if calculation fails
	 */
	public Calculation calculateTaxWithTenantStrategy(CalculationCriteria criteria, RequestInfo requestInfo, Map<String, Object> masterMap) {
		try {
			// Validate input
			if (criteria == null || criteria.getProperty() == null) {
				log.error("CalculationCriteria or Property is null");
				throw new CustomException("INVALID_CRITERIA", "CalculationCriteria and Property cannot be null");
			}

			Property property = criteria.getProperty();
			estimationCommonUtil.validateProperty(property);

			// Extract locality code
			//String localityCode = estimationCommonUtil.extractLocalityCode(property);
			String tenantId = property.getTenantId();
			String propertyId = property.getPropertyId();

			// Log calculation start
			log.info("Starting tenant-based tax calculation for PropertyId: {}, TenantId: {}", propertyId, tenantId);

			// Resolve appropriate strategy
			TenantBasedEstimationStrategy strategy = strategyFactory.resolveStrategy(tenantId);

			// Log strategy resolution
			estimationCommonUtil.logCalculationStart(tenantId, strategy.getStrategyName(), propertyId);

			// Calculate tax using resolved strategy
			Calculation calculation = strategy.calculateTax(criteria, requestInfo, masterMap);

			// Log calculation completion
			estimationCommonUtil.logCalculationComplete(strategy.getStrategyName(), calculation.getTotalAmount(), propertyId);

			log.info("Tax calculation completed successfully for PropertyId: {} using strategy: {}",
				propertyId, strategy.getStrategyName());

			return calculation;

		} catch (CustomException ce) {
			log.error("Custom exception during tax calculation: {}", ce.getMessage(), ce);
			throw ce;
		} catch (Exception e) {
			log.error("Unexpected error during tax calculation: {}", e.getMessage(), e);
			throw new CustomException("TAX_CALCULATION_ERROR", "Error calculating tax: " + e.getMessage());
		}
	}

	/**
	 * Gets the strategy name that would be used for given tenant
	 * Useful for debugging and logging
	 *
	 * @param tenantId the tenant code
	 * @return strategy name
	 */
	public String getStrategyNameForTenant(String tenantId) {
		try {
			TenantBasedEstimationStrategy strategy = strategyFactory.resolveStrategy(tenantId);
			return strategy.getStrategyName();
		} catch (Exception e) {
			log.error("Error getting strategy name for tenant: {}. Error: {}", tenantId, e.getMessage());
			return "DEFAULT";
		}
	}

	/**
	 * Checks if tenant-specific strategy exists for given tenant
	 *
	 * @param tenantId the tenant code
	 * @return true if specific strategy exists
	 */
	public boolean hasSpecificStrategyForTenantId(String tenantId) {
		return strategyFactory.hasTenantSpecificStrategy(tenantId);
	}
}

