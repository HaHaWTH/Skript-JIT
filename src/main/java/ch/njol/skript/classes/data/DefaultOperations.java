package ch.njol.skript.classes.data;

import ch.njol.skript.util.Date;
import ch.njol.skript.util.Timespan;
import ch.njol.skript.util.Utils;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.ApiStatus;
import org.skriptlang.skript.lang.arithmetic.Arithmetics;
import org.skriptlang.skript.lang.arithmetic.Operation;
import org.skriptlang.skript.lang.arithmetic.Operator;

public class DefaultOperations {

	/** The default numeric addition, exposed by identity for compiler operation selection. */
	@ApiStatus.Internal
	public static final Operation<Number, Number, Number> ADD_NUMBERS = (left, right) -> {
		if (Utils.isInteger(left, right)) {
			long result = left.longValue() + right.longValue();
			// catches overflow, from Math.addExact(long, long)
			if (((left.longValue() ^ result) & (right.longValue() ^ result)) >= 0)
				return result;
		}
		return left.doubleValue() + right.doubleValue();
	};

	@ApiStatus.Internal
	public static final Operation<Number, Number, Number> SUBTRACT_NUMBERS = (left, right) -> {
		if (Utils.isInteger(left, right)) {
			long result = left.longValue() - right.longValue();
			// Retain the existing choice of result type for subtraction.
			if (((left.longValue() ^ result) & (right.longValue() ^ result)) >= 0)
				return result;
		}
		return left.doubleValue() - right.doubleValue();
	};

	@ApiStatus.Internal
	public static final Operation<Number, Number, Number> MULTIPLY_NUMBERS = (left, right) -> {
		if (!Utils.isInteger(left, right))
			return left.doubleValue() * right.doubleValue();

		// catch overflow, from Math.multiplyExact(long, long)
		long longLeft = left.longValue();
		long longRight = right.longValue();
		long ax = Math.abs(longLeft);
		long ay = Math.abs(longRight);

		long result = left.longValue() * right.longValue();
		if (((ax | ay) >>> 31 != 0)) {
			// Check large operands using the divide operator, including Long.MIN_VALUE * -1.
			if (((longRight != 0) && (result / longRight != longLeft)) ||
					(longLeft == Long.MIN_VALUE && longRight == -1)) {
				return left.doubleValue() * right.doubleValue();
			}
		}
		return result;
	};

	@ApiStatus.Internal
	public static final Operation<Number, Number, Number> DIVIDE_NUMBERS =
		(left, right) -> left.doubleValue() / right.doubleValue();

	static {
		// Number - Number
		Arithmetics.registerOperation(Operator.ADDITION, Number.class, ADD_NUMBERS);
		Arithmetics.registerOperation(Operator.SUBTRACTION, Number.class, SUBTRACT_NUMBERS);
		Arithmetics.registerOperation(Operator.MULTIPLICATION, Number.class, MULTIPLY_NUMBERS);
		Arithmetics.registerOperation(Operator.DIVISION, Number.class, DIVIDE_NUMBERS);
		Arithmetics.registerOperation(Operator.EXPONENTIATION, Number.class,
			(left, right) -> Math.pow(left.doubleValue(), right.doubleValue()));
		Arithmetics.registerDifference(Number.class, (left, right) -> {
			double result = Math.abs(left.doubleValue() - right.doubleValue());
			if (Utils.isInteger(left, right) && result < Long.MAX_VALUE && result > Long.MIN_VALUE)
				return (long) result;
			return result;
		});
		Arithmetics.registerDefaultValue(Number.class, () -> 0L);

		// Vector - Vector
		Arithmetics.registerOperation(Operator.ADDITION, Vector.class, (left, right) -> left.clone().add(right));
		Arithmetics.registerOperation(Operator.SUBTRACTION, Vector.class,
			(left, right) -> left.clone().subtract(right));
		Arithmetics.registerOperation(Operator.MULTIPLICATION, Vector.class,
			(left, right) -> left.clone().multiply(right));
		Arithmetics.registerOperation(Operator.DIVISION, Vector.class, (left, right) -> left.clone().divide(right));
		Arithmetics.registerDifference(Vector.class,
			(left, right) -> new Vector(
				Math.abs(left.getX() - right.getX()),
				Math.abs(left.getY() - right.getY()),
				Math.abs(left.getZ() - right.getZ())
			));
		Arithmetics.registerDefaultValue(Vector.class, Vector::new);

		// Vector - Number
		// Number - Vector
		Arithmetics.registerOperation(Operator.MULTIPLICATION, Vector.class, Number.class,
			(left, right) -> left.clone().multiply(right.doubleValue()),
			(left, right) -> {
				double number = left.doubleValue();
				Vector leftVector = new Vector(number, number, number);
				return leftVector.multiply(right);
			}
		);
		Arithmetics.registerOperation(Operator.DIVISION, Vector.class, Number.class, (left, right) -> {
			double number = right.doubleValue();
			Vector rightVector = new Vector(number, number, number);
			return left.clone().divide(rightVector);
		}, (left, right) -> {
			double number = left.doubleValue();
			Vector leftVector = new Vector(number, number, number);
			return leftVector.divide(right);
		});

		// Timespan - Timespan
		Arithmetics.registerOperation(Operator.ADDITION, Timespan.class, Timespan::add);
		Arithmetics.registerOperation(Operator.SUBTRACTION, Timespan.class, Timespan::subtract);
		Arithmetics.registerOperation(Operator.DIVISION, Timespan.class, Timespan.class, Number.class,
			Timespan::divide);
		Arithmetics.registerDifference(Timespan.class, Timespan::difference);
		Arithmetics.registerDefaultValue(Timespan.class, Timespan::new);

		// Timespan - Number
		// Number - Timespan
		Arithmetics.registerOperation(Operator.MULTIPLICATION, Timespan.class, Number.class, (left, right) -> {
			double scalar = right.doubleValue();
			if (scalar < 0 || Double.isNaN(scalar))
				return null;
			return left.multiply(scalar);
		}, (left, right) -> {
			double scalar = left.doubleValue();
			if (scalar < 0 || Double.isNaN(scalar))
				return null;
			return right.multiply(scalar);
		});
		Arithmetics.registerOperation(Operator.DIVISION, Timespan.class, Number.class, (left, right) -> {
			double scalar = right.doubleValue();
			if (scalar < 0 || Double.isNaN(scalar))
				return null;
			return left.divide(scalar);
		});

		// Date - Timespan
		Arithmetics.registerOperation(Operator.ADDITION, Date.class, Timespan.class, Date::plus);
		Arithmetics.registerOperation(Operator.SUBTRACTION, Date.class, Timespan.class, Date::minus);
		Arithmetics.registerDifference(Date.class, Timespan.class, Date::difference);

		// String - String
		Arithmetics.registerOperation(Operator.ADDITION, String.class, String.class, String::concat);

	}

}
