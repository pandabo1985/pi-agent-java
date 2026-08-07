package com.duokanbook.pi.agent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.math.BigInteger;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Validates and coerces JSON values using the JSON Schema keywords accepted by agent tools.
 *
 * <p>The TypeScript runtime first clones and coerces tool arguments before validation. This
 * dependency-free counterpart follows the same rule for JSON-object schemas, including nested
 * objects and arrays, unions, and {@code additionalProperties}.
 */
final class JsonSchemaValidator {
	private static final Pattern DECIMAL_NUMBER = Pattern.compile("[+-]?(?:\\d+\\.?\\d*|\\.\\d+)(?:[eE][+-]?\\d+)?");
	private static final Pattern HEX_NUMBER = Pattern.compile("0[xX][0-9a-fA-F]+");
	private static final Pattern BINARY_NUMBER = Pattern.compile("0[bB][01]+");
	private static final Pattern OCTAL_NUMBER = Pattern.compile("0[oO][0-7]+");

	private JsonSchemaValidator() {}

	static Object coerceAndValidate(String toolName, Object arguments, Map<String, Object> schema) {
		Object coerced = coerce(copy(arguments), schema, schema);
		List<String> errors = new ArrayList<>();
		validate(coerced, schema, schema, "root", errors);
		if (!errors.isEmpty()) {
			throw new IllegalArgumentException(
					"Validation failed for tool \"" + toolName + "\":\n  - " + String.join("\n  - ", errors)
							+ "\n\nReceived arguments:\n" + Json.stringify(arguments));
		}
		return coerced;
	}

	private static Object coerce(Object value, Object rawSchema, Map<String, Object> root) {
		Object resolved = resolve(rawSchema, root);
		if (!(resolved instanceof Map<?, ?>)) return value;
		Map<String, Object> schema = map(resolved);

		for (Object nested : list(schema.get("allOf"))) value = coerce(value, nested, root);
		value = coerceUnion(value, list(schema.get("anyOf")), root);
		value = coerceUnion(value, list(schema.get("oneOf")), root);

		List<String> types = types(schema.get("type"));
		if (types.size() <= 1 || !matchesAnyType(value, types)) {
			for (String type : types) {
				Object candidate = coercePrimitive(value, type);
				if (!jsonEquals(candidate, value)) {
					value = candidate;
					break;
				}
			}
		}

		Map<String, Object> object = object(value);
		if (object != null && (types.isEmpty() || types.contains("object"))) {
			Map<String, Object> properties = map(schema.get("properties"));
			for (Map.Entry<String, Object> entry : properties.entrySet()) {
				if (object.containsKey(entry.getKey())) {
					object.put(entry.getKey(), coerce(object.get(entry.getKey()), entry.getValue(), root));
				}
			}
			Object additional = schema.get("additionalProperties");
			if (additional instanceof Map<?, ?>) {
				for (Map.Entry<String, Object> entry : object.entrySet()) {
					if (!properties.containsKey(entry.getKey())) {
						entry.setValue(coerce(entry.getValue(), additional, root));
					}
				}
			}
		}

		if (value instanceof List<?> && (types.isEmpty() || types.contains("array"))) {
			@SuppressWarnings("unchecked")
			List<Object> array = (List<Object>) value;
			Object items = schema.get("items");
			if (items instanceof List<?>) {
				List<?> itemSchemas = (List<?>) items;
				for (int i = 0; i < array.size() && i < itemSchemas.size(); i++) {
					array.set(i, coerce(array.get(i), itemSchemas.get(i), root));
				}
			} else if (items != null) {
				for (int i = 0; i < array.size(); i++) array.set(i, coerce(array.get(i), items, root));
			}
		}
		return value;
	}

	private static Object coerceUnion(Object value, List<Object> schemas, Map<String, Object> root) {
		if (schemas.isEmpty()) return value;
		for (Object schema : schemas) {
			if (isValid(value, schema, root)) return value;
		}
		for (Object schema : schemas) {
			Object candidate = coerce(copy(value), schema, root);
			if (isValid(candidate, schema, root)) return candidate;
		}
		return value;
	}

	private static Object coercePrimitive(Object value, String type) {
		switch (type) {
			case "number":
			case "integer":
				if (value == null) {
					return type.equals("integer") ? Long.valueOf(0L) : Double.valueOf(0.0);
				}
				if (value instanceof Boolean) {
					boolean b = ((Boolean) value).booleanValue();
					if (type.equals("integer")) return Long.valueOf(b ? 1L : 0L);
					return Double.valueOf(b ? 1.0 : 0.0);
				}
				if (value instanceof String && !((String) value).trim().isEmpty()) {
					Double parsed = parseJavaScriptNumber((String) value);
					if (parsed != null && Double.isFinite(parsed.doubleValue())
							&& (!type.equals("integer") || isWhole(parsed.doubleValue()))) {
						if (type.equals("integer")) return Long.valueOf(parsed.longValue());
						return parsed;
					}
				}
				break;
			case "boolean":
				if (value == null) return false;
				if ("true".equals(value)) return true;
				if ("false".equals(value)) return false;
				if (value instanceof Number && ((Number) value).doubleValue() == 1.0) return true;
				if (value instanceof Number && ((Number) value).doubleValue() == 0.0) return false;
				break;
			case "string":
				if (value == null) return "";
				if (value instanceof Boolean) return String.valueOf(value);
				if (value instanceof Number) return numberString((Number) value);
				break;
			case "null":
				if ("".equals(value) || (value instanceof Number && ((Number) value).doubleValue() == 0.0)
						|| Boolean.FALSE.equals(value)) return null;
				break;
			default:
				// Object and array values do not have safe JSON coercions.
				break;
		}
		return value;
	}

	private static Double parseJavaScriptNumber(String raw) {
		String value = raw.trim();
		try {
			if (DECIMAL_NUMBER.matcher(value).matches()) return Double.valueOf(Double.parseDouble(value));
			if (HEX_NUMBER.matcher(value).matches()) return Double.valueOf(new BigInteger(value.substring(2), 16).doubleValue());
			if (BINARY_NUMBER.matcher(value).matches()) return Double.valueOf(new BigInteger(value.substring(2), 2).doubleValue());
			if (OCTAL_NUMBER.matcher(value).matches()) return Double.valueOf(new BigInteger(value.substring(2), 8).doubleValue());
		} catch (NumberFormatException ignored) {
			// Leave values that cannot be safely parsed for validation to reject.
		}
		return null;
	}

	private static void validate(
			Object value, Object rawSchema, Map<String, Object> root, String path, List<String> errors) {
		Object resolved = resolve(rawSchema, root);
		if (resolved instanceof Boolean) {
			boolean allowed = ((Boolean) resolved).booleanValue();
			if (!allowed) errors.add(path + ": value is not allowed");
			return;
		}
		if (!(resolved instanceof Map<?, ?>)) return;
		Map<String, Object> schema = map(resolved);

		for (Object nested : list(schema.get("allOf"))) validate(value, nested, root, path, errors);
		validateUnion(value, list(schema.get("anyOf")), root, path, errors, "anyOf", false);
		validateUnion(value, list(schema.get("oneOf")), root, path, errors, "oneOf", true);
		if (schema.containsKey("not") && isValid(value, schema.get("not"), root)) {
			errors.add(path + ": must not match the disallowed schema");
		}
		if (schema.containsKey("if")) {
			validate(value, isValid(value, schema.get("if"), root) ? schema.get("then") : schema.get("else"), root, path, errors);
		}

		if (schema.containsKey("const") && !jsonEquals(value, schema.get("const"))) {
			errors.add(path + ": must equal the configured constant");
		}
		List<Object> enumValues = list(schema.get("enum"));
		if (!enumValues.isEmpty() && enumValues.stream().noneMatch(candidate -> jsonEquals(value, candidate))) {
			errors.add(path + ": must be one of the configured enum values");
		}

		List<String> types = types(schema.get("type"));
		if (!types.isEmpty() && !matchesAnyType(value, types)) {
			errors.add(path + ": expected " + String.join(" or ", types));
			return;
		}

		Map<String, Object> object = object(value);
		if (object != null) validateObject(object, schema, root, path, errors);
		if (value instanceof List<?>) validateArray((List<?>) value, schema, root, path, errors);
		if (value instanceof String) validateString((String) value, schema, path, errors);
		if (value instanceof Number) validateNumber((Number) value, schema, path, errors);
	}

	private static void validateUnion(
			Object value,
			List<Object> schemas,
			Map<String, Object> root,
			String path,
			List<String> errors,
			String keyword,
			boolean exactlyOne) {
		if (schemas.isEmpty()) return;
		int matches = 0;
		for (Object schema : schemas) if (isValid(value, schema, root)) matches++;
		if ((!exactlyOne && matches == 0) || (exactlyOne && matches != 1)) {
			errors.add(path + ": must match " + (exactlyOne ? "exactly one" : "at least one") + " " + keyword + " schema");
		}
	}

	private static void validateObject(
			Map<String, Object> value, Map<String, Object> schema, Map<String, Object> root, String path, List<String> errors) {
		checkSize(value.size(), schema, "minProperties", "maxProperties", path, errors, "properties");
		Map<String, Object> properties = map(schema.get("properties"));
		for (Object required : list(schema.get("required"))) {
			if (required instanceof String && !value.containsKey((String) required)) {
				errors.add(child(path, required) + ": is required");
			}
		}
		for (Map.Entry<String, Object> property : properties.entrySet()) {
			if (value.containsKey(property.getKey())) {
				validate(value.get(property.getKey()), property.getValue(), root, child(path, property.getKey()), errors);
			}
		}
		Object additional = schema.get("additionalProperties");
		for (Map.Entry<String, Object> entry : value.entrySet()) {
			if (properties.containsKey(entry.getKey())) continue;
			if (Boolean.FALSE.equals(additional)) {
				errors.add(child(path, entry.getKey()) + ": additional property is not allowed");
			} else if (additional != null) {
				validate(entry.getValue(), additional, root, child(path, entry.getKey()), errors);
			}
		}
	}

	private static void validateArray(
			List<?> value, Map<String, Object> schema, Map<String, Object> root, String path, List<String> errors) {
		checkSize(value.size(), schema, "minItems", "maxItems", path, errors, "items");
		if (Boolean.TRUE.equals(schema.get("uniqueItems"))) {
			for (int i = 0; i < value.size(); i++) {
				for (int j = 0; j < i; j++) {
					if (jsonEquals(value.get(i), value.get(j))) {
						errors.add(path + ": items must be unique");
						i = value.size();
						break;
					}
				}
			}
		}
		Object items = schema.get("items");
		if (items instanceof List<?>) {
			List<?> tuple = (List<?>) items;
			for (int i = 0; i < value.size() && i < tuple.size(); i++) validate(value.get(i), tuple.get(i), root, child(path, i), errors);
		} else if (items != null) {
			for (int i = 0; i < value.size(); i++) validate(value.get(i), items, root, child(path, i), errors);
		}
	}

	private static void validateString(String value, Map<String, Object> schema, String path, List<String> errors) {
		checkSize(value.length(), schema, "minLength", "maxLength", path, errors, "characters");
		Object pattern = schema.get("pattern");
		if (pattern instanceof String) {
			String expression = (String) pattern;
			try {
				if (!Pattern.compile(expression).matcher(value).find()) errors.add(path + ": does not match the required pattern");
			} catch (PatternSyntaxException e) {
				errors.add(path + ": schema contains an invalid pattern");
			}
		}
	}

	private static void validateNumber(Number value, Map<String, Object> schema, String path, List<String> errors) {
		double number = value.doubleValue();
		checkBound(number, schema.get("minimum"), false, path, errors, "must be >=");
		checkBound(number, schema.get("maximum"), true, path, errors, "must be <=");
		checkBound(number, schema.get("exclusiveMinimum"), false, path, errors, "must be >");
		checkBound(number, schema.get("exclusiveMaximum"), true, path, errors, "must be <");
		if (schema.get("multipleOf") instanceof Number && ((Number) schema.get("multipleOf")).doubleValue() != 0.0) {
			Number multiple = (Number) schema.get("multipleOf");
			double quotient = number / multiple.doubleValue();
			if (Math.abs(quotient - Math.rint(quotient)) > 1e-9) errors.add(path + ": must be a multiple of " + multiple);
		}
	}

	private static void checkBound(
			double value, Object rawBound, boolean upper, String path, List<String> errors, String description) {
		if (!(rawBound instanceof Number)) return;
		Number bound = (Number) rawBound;
		boolean invalid = upper ? value > bound.doubleValue() : value < bound.doubleValue();
		if (description.endsWith(">") || description.endsWith("<")) {
			invalid = upper ? value >= bound.doubleValue() : value <= bound.doubleValue();
		}
		if (invalid) errors.add(path + ": " + description + " " + bound);
	}

	private static void checkSize(
			int size, Map<String, Object> schema, String minName, String maxName, String path, List<String> errors, String noun) {
		if (schema.get(minName) instanceof Number && size < ((Number) schema.get(minName)).intValue()) {
			errors.add(path + ": must have at least " + schema.get(minName) + " " + noun);
		}
		if (schema.get(maxName) instanceof Number && size > ((Number) schema.get(maxName)).intValue()) {
			errors.add(path + ": must have at most " + schema.get(maxName) + " " + noun);
		}
	}

	private static boolean isValid(Object value, Object schema, Map<String, Object> root) {
		List<String> errors = new ArrayList<>();
		validate(value, schema, root, "root", errors);
		return errors.isEmpty();
	}

	private static boolean matchesAnyType(Object value, List<String> types) {
		for (String type : types) if (matchesType(value, type)) return true;
		return false;
	}

	private static boolean matchesType(Object value, String type) {
		if ("object".equals(type)) return object(value) != null;
		if ("array".equals(type)) return value instanceof List<?>;
		if ("string".equals(type)) return value instanceof String;
		if ("boolean".equals(type)) return value instanceof Boolean;
		if ("null".equals(type)) return value == null;
		if ("number".equals(type)) return value instanceof Number && Double.isFinite(((Number) value).doubleValue());
		if ("integer".equals(type)) return value instanceof Number && Double.isFinite(((Number) value).doubleValue()) && isWhole(((Number) value).doubleValue());
		return false;
	}

	private static Object resolve(Object schema, Map<String, Object> root) {
		if (!(schema instanceof Map<?, ?>)) return schema;
		Object reference = ((Map<?, ?>) schema).get("$ref");
		if (!(reference instanceof String)) return schema;
		String ref = (String) reference;
		if (!ref.startsWith("#/")) return schema;
		Object current = root;
		for (String part : ref.substring(2).split("/")) {
			Map<String, Object> map = object(current);
			if (map == null) return schema;
			current = map.get(part.replace("~1", "/").replace("~0", "~"));
		}
		return current != null ? current : schema;
	}

	private static List<String> types(Object raw) {
		List<String> result = new ArrayList<>();
		if (raw instanceof String) result.add((String) raw);
		else for (Object type : list(raw)) if (type instanceof String) result.add((String) type);
		return result;
	}

	private static String child(String path, Object component) {
		return path.equals("root") ? String.valueOf(component) : path + "." + component;
	}

	private static boolean isWhole(double value) {
		return Math.rint(value) == value;
	}

	private static String numberString(Number number) {
		double value = number.doubleValue();
		return isWhole(value) ? Long.toString((long) value) : Double.toString(value);
	}

	private static Object copy(Object value) {
		if (value instanceof Map<?, ?>) {
			Map<?, ?> source = (Map<?, ?>) value;
			Map<String, Object> result = new LinkedHashMap<>();
			for (Map.Entry<?, ?> entry : source.entrySet()) result.put(String.valueOf(entry.getKey()), copy(entry.getValue()));
			return result;
		}
		if (value instanceof List<?>) {
			List<?> source = (List<?>) value;
			List<Object> result = new ArrayList<>(source.size());
			for (Object item : source) result.add(copy(item));
			return result;
		}
		return value;
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> map(Object value) {
		return value instanceof Map<?, ?> ? (Map<String, Object>) value : Collections.<String, Object>emptyMap();
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> object(Object value) {
		return value instanceof Map<?, ?> ? (Map<String, Object>) value : null;
	}

	@SuppressWarnings("unchecked")
	private static List<Object> list(Object value) {
		return value instanceof List<?> ? (List<Object>) value : Collections.<Object>emptyList();
	}

	private static boolean jsonEquals(Object left, Object right) {
		if (left instanceof Number && right instanceof Number) return Double.compare(((Number) left).doubleValue(), ((Number) right).doubleValue()) == 0;
		if (left instanceof List<?> && right instanceof List<?>) {
			List<?> a = (List<?>) left;
			List<?> b = (List<?>) right;
			if (a.size() != b.size()) return false;
			for (int i = 0; i < a.size(); i++) if (!jsonEquals(a.get(i), b.get(i))) return false;
			return true;
		}
		if (left instanceof Map<?, ?> && right instanceof Map<?, ?>) {
			Map<?, ?> a = (Map<?, ?>) left;
			Map<?, ?> b = (Map<?, ?>) right;
			if (a.size() != b.size() || !a.keySet().equals(b.keySet())) return false;
			for (Object key : a.keySet()) if (!jsonEquals(a.get(key), b.get(key))) return false;
			return true;
		}
		return java.util.Objects.equals(left, right);
	}
}
