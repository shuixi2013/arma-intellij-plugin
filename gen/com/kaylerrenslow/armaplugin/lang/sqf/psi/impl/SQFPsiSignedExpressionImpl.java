// This is a generated file. Not intended for manual editing.
package com.kaylerrenslow.armaplugin.lang.sqf.psi.impl;

import java.util.List;
import org.jetbrains.annotations.*;
import com.intellij.lang.ASTNode;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.util.PsiTreeUtil;
import static com.kaylerrenslow.armaplugin.lang.sqf.psi.SQFTypes.*;
import com.kaylerrenslow.armaplugin.lang.sqf.psi.SQFSignedExpression;
import com.kaylerrenslow.armaplugin.lang.sqf.psi.*;

public class SQFPsiSignedExpressionImpl extends SQFSignedExpression implements SQFPsiSignedExpression {

  public SQFPsiSignedExpressionImpl(ASTNode node) {
    super(node);
  }

  public void accept(@NotNull SQFPsiVisitor visitor) {
    visitor.visitSignedExpression(this);
  }

  public void accept(@NotNull PsiElementVisitor visitor) {
    if (visitor instanceof SQFPsiVisitor) accept((SQFPsiVisitor)visitor);
    else super.accept(visitor);
  }

  @Override
  @NotNull
  public SQFPsiExpression getExpression() {
    return findNotNullChildByClass(SQFPsiExpression.class);
  }

}
