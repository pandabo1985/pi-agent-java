package com.duokanbook.pi.agent;

import java.util.Arrays;

/** Java 8 replacement for the value semantics supplied by Java records. */
abstract class ValueObject {

	protected abstract String[] componentNames();

	protected abstract Object[] componentValues();

	@Override
	public final boolean equals(Object other) {
		return this == other || (other != null && getClass() == other.getClass()
				&& Arrays.equals(componentValues(), ((ValueObject) other).componentValues()));
	}

	@Override
	public final int hashCode() {
		return Arrays.hashCode(componentValues());
	}

	@Override
	public final String toString() {
		String className = getClass().getName();
		int packageEnd = className.lastIndexOf('.');
		StringBuilder result = new StringBuilder(className.substring(packageEnd + 1).replace('$', '.')).append('[');
		String[] names = componentNames();
		Object[] values = componentValues();
		for (int i = 0; i < names.length; i++) {
			if (i > 0) result.append(", ");
			result.append(names[i]).append('=').append(values[i]);
		}
		return result.append(']').toString();
	}
}
