package com.kaylerrenslow.armaplugin.lang.sqf.psi;

import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.psi.PsiElement;
import com.kaylerrenslow.armaplugin.lang.sqf.syntax.CommandDescriptorCluster;
import com.kaylerrenslow.armaplugin.lang.sqf.syntax.ValueType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.List;

/**
 * @author Kayler
 * @since 11/14/2017
 */
public class SQFSyntaxChecker implements SQFSyntaxVisitor<ValueType> {
	@NotNull
	private final List<SQFStatement> statements;
	@NotNull
	private final CommandDescriptorCluster cluster;
	@NotNull
	private final ProblemsHolder problems;

	public SQFSyntaxChecker(@NotNull List<SQFStatement> statements, @NotNull CommandDescriptorCluster cluster,
							@NotNull ProblemsHolder holder) {
		this.statements = statements;
		this.cluster = cluster;
		this.problems = holder;
	}

	public void begin() {
		for (SQFStatement statement : statements) {
			statement.accept(this, cluster);
		}
	}

	@Nullable
	@Override
	public ValueType visit(@NotNull SQFAssignmentStatement statement, @NotNull CommandDescriptorCluster cluster) {
		SQFExpression expr = statement.getExpr();
		if (expr != null) {
			expr.accept(this, cluster);
		}

		return ValueType.NOTHING;
	}

	@Nullable
	@Override
	public ValueType visit(@NotNull SQFCaseStatement statement, @NotNull CommandDescriptorCluster cluster) {
		SQFExpression cond = statement.getCondition();
		if (cond != null) {
			cond.accept(this, cluster);
		}
		SQFCodeBlock block = statement.getBlock();
		if (block != null) {
			SQFLocalScope scope = block.getScope();
			if (scope != null) {
				scope.accept(this, cluster);
			}
		}

		return ValueType.NOTHING;
	}

	@Nullable
	@Override
	public ValueType visit(@NotNull SQFExpressionStatement statement, @NotNull CommandDescriptorCluster cluster) {
		return (ValueType) statement.getExpr().accept(this, cluster);
	}

	@Nullable
	@Override
	public ValueType visit(@NotNull SQFQuestStatement statement, @NotNull CommandDescriptorCluster cluster) {
		SQFExpression cond = statement.getCondition();
		if (cond != null) {
			cond.accept(this, cluster);
		}
		SQFExpression ifTrue = statement.getIfTrueExpr();
		if (ifTrue != null) {
			ifTrue.accept(this, cluster);
		}

		return ValueType.NOTHING;
	}

	@Nullable
	@Override
	public ValueType visit(@NotNull SQFAddExpression expr, @NotNull CommandDescriptorCluster cluster) {
		SQFExpression leftExpr = expr.getLeft();
		ValueType left = null, right = null;
		if (leftExpr != null) {
			left = (ValueType) leftExpr.accept(this, cluster);
		}

		SQFExpression rightExpr = expr.getRight();
		if (rightExpr != null) {
			right = (ValueType) rightExpr.accept(this, cluster);
		}

		if (left == null || right == null) {
			//can't be determined
			return ValueType.ANYTHING;
		}

		switch (left) {
			case NUMBER: //fall
			case STRING: {
				assertIsType(right, left, rightExpr);
				break;
			}
			default: {
				if (left.isArray() && !right.isArray()) {
					problems.registerProblem(rightExpr, "Not an Array type.", ProblemHighlightType.ERROR);
					return ValueType.ARRAY;
				}
				notOfType(new ValueType[]{
								ValueType.NUMBER,
								ValueType.STRING,
								ValueType.ARRAY
						}, right, rightExpr
				);
				return ValueType.ANYTHING;
			}
		}

		return ValueType.NUMBER;
	}

	@Nullable
	@Override
	public ValueType visit(@NotNull SQFSubExpression expr, @NotNull CommandDescriptorCluster cluster) {
		SQFExpression leftExpr = expr.getLeft();
		ValueType left = null, right = null;
		if (leftExpr != null) {
			left = (ValueType) leftExpr.accept(this, cluster);
		}

		SQFExpression rightExpr = expr.getRight();
		if (rightExpr != null) {
			right = (ValueType) rightExpr.accept(this, cluster);
		}

		if (left == null || right == null) {
			//can't be determined
			return ValueType.ANYTHING;
		}

		switch (left) {
			case NUMBER: {
				assertIsType(right, ValueType.NUMBER, rightExpr);
				break;
			}
			default: {
				if (left.isArray() && !right.isArray()) {
					problems.registerProblem(rightExpr, "Not an Array type.", ProblemHighlightType.ERROR);
					return ValueType.ARRAY;
				}
				notOfType(new ValueType[]{
								ValueType.NUMBER,
								ValueType.ARRAY
						}, right, rightExpr
				);
				return ValueType.ANYTHING;
			}
		}

		return left;
	}

	@Nullable
	@Override
	public ValueType visit(@NotNull SQFMultExpression expr, @NotNull CommandDescriptorCluster cluster) {
		return binaryExprSameTypeHelper(expr, ValueType.NUMBER, cluster);
	}

	@Nullable
	@Override
	public ValueType visit(@NotNull SQFDivExpression expr, @NotNull CommandDescriptorCluster cluster) {
		SQFExpression leftExpr = expr.getLeft();
		ValueType left = null, right = null;
		if (leftExpr != null) {
			left = (ValueType) leftExpr.accept(this, cluster);
		}

		SQFExpression rightExpr = expr.getRight();
		if (rightExpr != null) {
			right = (ValueType) rightExpr.accept(this, cluster);
		}

		if (left == null || right == null) {
			//can't be determined
			return ValueType.ANYTHING;
		}

		switch (left) {
			case NUMBER: {
				assertIsType(right, ValueType.NUMBER, rightExpr);
				return ValueType.NUMBER;
			}
			case CONFIG: {
				assertIsType(right, ValueType.STRING, rightExpr);
				return ValueType.CONFIG;
			}
			default: {
				notOfType(new ValueType[]{
								ValueType.NUMBER,
								ValueType.STRING
						}, right, rightExpr
				);
				return ValueType.ANYTHING;
			}
		}
	}

	@Nullable
	@Override
	public ValueType visit(@NotNull SQFModExpression expr, @NotNull CommandDescriptorCluster cluster) {
		return binaryExprSameTypeHelper(expr, ValueType.NUMBER, cluster);
	}

	@Nullable
	@Override
	public ValueType visit(@NotNull SQFBoolAndExpression expr, @NotNull CommandDescriptorCluster cluster) {
		SQFExpression leftExpr = expr.getLeft();
		ValueType left = null, right = null;
		if (leftExpr != null) {
			left = (ValueType) leftExpr.accept(this, cluster);
		}

		SQFExpression rightExpr = expr.getRight();
		if (rightExpr != null) {
			right = (ValueType) rightExpr.accept(this, cluster);
		}

		if (left == null || right == null) {
			return ValueType.BOOLEAN;
		}

		assertIsType(left, ValueType.BOOLEAN, leftExpr);
		assertIsType(right, new ValueType[]{
						ValueType.BOOLEAN, ValueType.CODE
				}, rightExpr
		);

		return ValueType.BOOLEAN;
	}

	@Nullable
	@Override
	public ValueType visit(@NotNull SQFBoolOrExpression expr, @NotNull CommandDescriptorCluster cluster) {
		SQFExpression leftExpr = expr.getLeft();
		ValueType left = null, right = null;
		if (leftExpr != null) {
			left = (ValueType) leftExpr.accept(this, cluster);
		}

		SQFExpression rightExpr = expr.getRight();
		if (rightExpr != null) {
			right = (ValueType) rightExpr.accept(this, cluster);
		}

		if (left == null || right == null) {
			return ValueType.BOOLEAN;
		}

		assertIsType(left, ValueType.BOOLEAN, leftExpr);
		assertIsType(right, new ValueType[]{
						ValueType.BOOLEAN, ValueType.CODE
				}, rightExpr
		);

		return ValueType.BOOLEAN;
	}

	@Nullable
	@Override
	public ValueType visit(@NotNull SQFBoolNotExpression expr, @NotNull CommandDescriptorCluster cluster) {
		SQFExpression expr1 = expr.getExpr();
		if (expr1 != null) {
			ValueType type = (ValueType) expr1.accept(this, cluster);
			if (type != null) {
				assertIsType(type, ValueType.BOOLEAN, expr1);
			}
		}
		return ValueType.BOOLEAN;
	}

	@Nullable
	@Override
	public ValueType visit(@NotNull SQFCompExpression expr, @NotNull CommandDescriptorCluster cluster) {
		if (expr.getComparisonType() != SQFCompExpression.ComparisonType.Equals) {
			binaryExprSameTypeHelper(expr, ValueType.NUMBER, cluster);
			return ValueType.BOOLEAN;
		}

		SQFExpression leftExpr = expr.getLeft();
		ValueType left = null, right = null;
		if (leftExpr != null) {
			left = (ValueType) leftExpr.accept(this, cluster);
		}

		SQFExpression rightExpr = expr.getRight();
		if (rightExpr != null) {
			right = (ValueType) rightExpr.accept(this, cluster);
		}

		if (left == null || right == null) {
			//can't be determined
			return ValueType.ANYTHING;
		}
		ValueType[] allowedTypes = {
				ValueType.NUMBER,
				ValueType.GROUP,
				ValueType.SIDE,
				ValueType.STRING,
				ValueType.OBJECT,
				ValueType.STRUCTURED_TEXT,
				ValueType.CONFIG,
				ValueType.DISPLAY,
				ValueType.CONTROL,
				ValueType.LOCATION
		};
		for (ValueType type : allowedTypes) {
			if (left == type) {
				assertIsType(right, allowedTypes, rightExpr);
				return ValueType.BOOLEAN;
			}
		}
		notOfType(allowedTypes, left, leftExpr);
		assertIsType(right, allowedTypes, rightExpr);

		return ValueType.BOOLEAN;
	}

	@Nullable
	@Override
	public ValueType visit(@NotNull SQFConfigFetchExpression expr, @NotNull CommandDescriptorCluster cluster) {
		return null;
	}

	@Nullable
	@Override
	public ValueType visit(@NotNull SQFExponentExpression expr, @NotNull CommandDescriptorCluster cluster) {
		return null;
	}

	@Nullable
	@Override
	public ValueType visit(@NotNull SQFLiteralExpression expr, @NotNull CommandDescriptorCluster cluster) {
		return null;
	}

	@Nullable
	@Override
	public ValueType visit(@NotNull SQFParenExpression expr, @NotNull CommandDescriptorCluster cluster) {
		return null;
	}

	@Nullable
	@Override
	public ValueType visit(@NotNull SQFCommandExpression expr, @NotNull CommandDescriptorCluster cluster) {
		return null;
	}

	@Nullable
	@Override
	public ValueType visit(@NotNull SQFCodeBlockExpression expr, @NotNull CommandDescriptorCluster cluster) {
		return null;
	}

	@Nullable
	@Override
	public ValueType visit(@NotNull SQFSignedExpression expr, @NotNull CommandDescriptorCluster cluster) {
		return null;
	}

	@NotNull
	private ValueType binaryExprSameTypeHelper(@NotNull SQFBinaryExpression expr, @NotNull ValueType expected,
											   @NotNull CommandDescriptorCluster cluster) {
		SQFExpression leftExpr = expr.getLeft();
		ValueType left = null, right = null;
		if (leftExpr != null) {
			left = (ValueType) leftExpr.accept(this, cluster);
		}

		SQFExpression rightExpr = expr.getRight();
		if (rightExpr != null) {
			right = (ValueType) rightExpr.accept(this, cluster);
		}

		if (left == null || right == null) {
			//can't be determined
			return ValueType.ANYTHING;
		}

		assertIsType(left, expected, leftExpr);
		assertIsType(right, expected, rightExpr);

		return expected;
	}

	private void notOfType(@NotNull ValueType[] expected, @NotNull ValueType got, @NotNull PsiElement gotPsiOwner) {
		problems.registerProblem(gotPsiOwner, "Type(s) " + Arrays.toString(expected) + " expected. Got " + got + ".", ProblemHighlightType.ERROR);
	}

	private boolean assertIsType(@NotNull ValueType check, @NotNull ValueType[] expected, @NotNull PsiElement checkPsiOwner) {
		for (ValueType expect : expected) {
			if (check == expect) {
				return true;
			}
		}
		problems.registerProblem(checkPsiOwner, "Type(s) " + Arrays.toString(expected) + " expected. Got " + check + ".", ProblemHighlightType.ERROR);
		return false;
	}

	private boolean assertIsType(@NotNull ValueType check, @NotNull ValueType expected, @NotNull PsiElement checkPsiOwner) {
		if (check != expected) {
			problems.registerProblem(checkPsiOwner, "Type " + expected + " expected. Got " + check + ".", ProblemHighlightType.ERROR);
			return false;
		}
		return true;
	}
}