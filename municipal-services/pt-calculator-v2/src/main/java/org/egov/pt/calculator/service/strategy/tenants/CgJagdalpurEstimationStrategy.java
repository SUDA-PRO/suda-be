package org.egov.pt.calculator.service.strategy.tenants;

import java.util.Map;

import org.egov.common.contract.request.RequestInfo;
import org.egov.pt.calculator.service.strategy.TenantBasedEstimationStrategy;
import org.egov.pt.calculator.web.models.Calculation;
import org.egov.pt.calculator.web.models.CalculationCriteria;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

/**
 * CgJagdalpurEstimationStrategy - Example tenant-specific estimation strategy
 *
 * This is an example implementation showing how to create a tenant-specific strategy.
 * This strategy would be used for Jagdalpur (Chhattisgarh) properties.
 *
 * Bean name: cgJagdalpurEstimationStrategy (auto-discovered and injected)
 * Tenant codes: cg.jagdalpur
 *
 * To create similar strategy for other tenant:
 * 1. Create new class extending this pattern
 * 2. Update bean name accordingly (e.g., CgBhilaiEstimationStrategy)
 * 3. Implement custom calculation logic as needed
 * 4. Spring will automatically discover and register the bean
 *
 * @author PT Calculator Service
 * @version 1.0
 */
@Service("cgJagdalpurEstimationStrategy")
@Slf4j
public class CgJagdalpurEstimationStrategy implements TenantBasedEstimationStrategy {

	@Autowired
	private DefaultEstimationStrategy defaultEstimationStrategy;

	@Override
	public Calculation calculateTax(CalculationCriteria criteria, RequestInfo requestInfo, Map<String, Object> masterMap) throws Exception {
		log.info("CgJagdalpurEstimationStrategy - Calculating tax for Jagdalpur");
		try {
			// For now, delegate to default strategy
			// In future, this can have Jagdalpur-specific logic
			// Example custom logic:
			// - Apply city-specific surcharge/rebate
			// - Use different billing slabs
			// - Apply tenant-specific exemptions
			// - Custom calculation rules

			Calculation calculation = defaultEstimationStrategy.calculateTax(criteria, requestInfo, masterMap);
			log.info("CgJagdalpurEstimationStrategy - Tax calculation completed");
			return calculation;

		} catch (Exception e) {
			log.error("Error in CgJagdalpurEstimationStrategy: {}", e.getMessage(), e);
			throw e;
		}
	}

	@Override
	public String getStrategyName() {
		return "CG_JAGDALPUR_ESTIMATION_STRATEGY";
	}

	@Override
	public boolean isDefault() {
		return false;
	}
}

