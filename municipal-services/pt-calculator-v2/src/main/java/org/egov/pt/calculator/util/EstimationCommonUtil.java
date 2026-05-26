package org.egov.pt.calculator.util;

import java.math.BigDecimal;
import java.util.*;

import org.apache.commons.lang3.StringUtils;
import org.egov.pt.calculator.web.models.property.Property;
import org.egov.pt.calculator.web.models.property.PropertyDetail;
import org.egov.tracer.model.CustomException;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * EstimationCommonUtil - Contains common utility methods for estimation calculations
 * This class holds shared logic across all estimation strategy implementations
 *
 * @author PT Calculator Service
 * @version 1.0
 */
@Component
@Slf4j
public class EstimationCommonUtil {

	/**
	 * Extracts locality code from property's address
	 * Format: state.city (e.g., cg.jagdalpur, cg.bhilai)
	 *
	 * @param property the property object
	 * @return locality code or empty string if not found
	 */
	public String extractLocalityCode(Property property) {
		try {
			if (property == null || property.getAddress() == null || property.getAddress().getLocality() == null) {
				log.warn("Property address or locality is null, returning default locality");
				return "";
			}
			String localityCode = property.getAddress().getLocality().getCode();
			log.info("Extracted locality code: {}", localityCode);
			return StringUtils.isNotEmpty(localityCode) ? localityCode : "";
		} catch (Exception e) {
			log.error("Error extracting locality code from property address: {}", e.getMessage(), e);
			return "";
		}
	}

	/**
	 * Validates if locality code matches the specified pattern
	 *
	 * @param tenantId the locality code to validate
	 * @return true if locality code is valid and not empty
	 */
	public boolean isValidTenantCode(String tenantId) {
		return StringUtils.isNotEmpty(tenantId) && tenantId.contains(".");
	}

	/**
	 * Gets bean name suffix from locality code
	 * Example: cg.jagdalpur → CgJagdalpur (camelCase with capitals)
	 *
	 * @param tenantId the locality code
	 * @return bean name suffix
	 */
	public String getBeanNameSuffix(String tenantId) {
		if (StringUtils.isEmpty(tenantId)) {
			return "";
		}
		try {
			String[] parts = tenantId.split("\\.");
			if (parts.length < 2) {
				return "";
			}
			String state = capitalize(parts[0]);
			String city = capitalize(parts[1]);
			String suffix = state + city;
			log.info("Generated bean name suffix from tenant code {}: {}", tenantId, suffix);
			return suffix;
		} catch (Exception e) {
			log.error("Error generating bean name suffix for tenant code: {}", tenantId, e);
			return "";
		}
	}

	/**
	 * Capitalizes the first letter of a string
	 *
	 * @param str the string to capitalize
	 * @return capitalized string
	 */
	private String capitalize(String str) {
		if (StringUtils.isEmpty(str)) {
			return str;
		}
		return str.substring(0, 1).toUpperCase() + str.substring(1).toLowerCase();
	}

	/**
	 * Validates property and its details for calculation
	 *
	 * @param property the property to validate
	 * @throws CustomException if property is invalid
	 */
	public void validateProperty(Property property) {
		if (property == null) {
			log.error("Property object is null");
			throw new CustomException("INVALID_PROPERTY", "Property cannot be null");
		}
		if (property.getPropertyDetails() == null || property.getPropertyDetails().isEmpty()) {
			log.error("Property details list is empty or null");
			throw new CustomException("INVALID_PROPERTY_DETAILS", "Property must have at least one detail");
		}
	}

	/**
	 * Gets property detail safely
	 *
	 * @param property the property object
	 * @return first property detail
	 * @throws CustomException if property details are not available
	 */
	public PropertyDetail getPropertyDetail(Property property) {
		validateProperty(property);
		PropertyDetail detail = property.getPropertyDetails().get(0);
		if (detail == null) {
			log.error("First property detail is null");
			throw new CustomException("INVALID_PROPERTY_DETAIL", "Property detail cannot be null");
		}
		return detail;
	}

	/**
	 * Safely retrieves master map value
	 *
	 * @param masterMap the master data map
	 * @param key the key to retrieve
	 * @return the value or null if not found
	 */
	public Object getSafeMasterValue(Map<String, Object> masterMap, String key) {
		if (masterMap == null || StringUtils.isEmpty(key)) {
			return null;
		}
		return masterMap.get(key);
	}

	/**
	 * Rounds BigDecimal to 2 decimal places
	 *
	 * @param value the value to round
	 * @return rounded value
	 */
	public BigDecimal roundToTwoDecimals(BigDecimal value) {
		if (value == null) {
			return BigDecimal.ZERO;
		}
		return value.setScale(2, BigDecimal.ROUND_HALF_UP);
	}

	/**
	 * Validates financial year format
	 * Expected format: YYYY-YY (e.g., 2023-24)
	 *
	 * @param financialYear the financial year to validate
	 * @return true if format is valid
	 */
	public boolean isValidFinancialYear(String financialYear) {
		if (StringUtils.isEmpty(financialYear)) {
			return false;
		}
		return financialYear.matches("\\d{4}-\\d{2}");
	}

	/**
	 * Logs calculation start with context information
	 *
	 * @param localityCode the locality code
	 * @param strategyName the estimation strategy being used
	 * @param propertyId the property ID
	 */
	public void logCalculationStart(String localityCode, String strategyName, String propertyId) {
		log.info("Starting tax calculation - Locality: {}, Strategy: {}, PropertyId: {}",
			localityCode, strategyName, propertyId);
	}

	/**
	 * Logs calculation completion with result
	 *
	 * @param strategyName the estimation strategy used
	 * @param totalAmount the calculated total amount
	 * @param propertyId the property ID
	 */
	public void logCalculationComplete(String strategyName, BigDecimal totalAmount, String propertyId) {
		log.info("Tax calculation completed - Strategy: {}, TotalAmount: {}, PropertyId: {}",
			strategyName, totalAmount, propertyId);
	}

	/**
	 * Logs strategy resolution details
	 *
	 * @param localityCode the locality code
	 * @param strategyUsed the strategy that will be used
	 * @param isDefault whether default strategy is being used
	 */
	public void logStrategyResolution(String localityCode, String strategyUsed, boolean isDefault) {
		String message = isDefault
			? "Using DEFAULT estimation strategy - no locality-specific implementation found for: {}"
			: "Using locality-specific estimation strategy: {} for locality: {}";
		if (isDefault) {
			log.info(message, localityCode);
		} else {
			log.info(message, strategyUsed, localityCode);
		}
	}
}

