package com.intellij.refactoring.inline;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.Ref;
import com.intellij.patterns.impl.Pattern;
import com.intellij.patterns.impl.StandardPatterns;
import static com.intellij.patterns.impl.StandardPatterns.psiElement;
import static com.intellij.patterns.impl.StandardPatterns.psiExpressionStatement;
import com.intellij.psi.*;
import com.intellij.psi.search.searches.ReferencesSearch;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.refactoring.BaseRefactoringProcessor;
import com.intellij.refactoring.RefactoringBundle;
import com.intellij.refactoring.ui.ConflictsDialog;
import com.intellij.refactoring.util.CommonRefactoringUtil;
import com.intellij.usageView.UsageInfo;
import com.intellij.usageView.UsageViewDescriptor;
import com.intellij.util.IncorrectOperationException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;

/**
 * @author yole
 */
public class InlineToAnonymousClassProcessor extends BaseRefactoringProcessor {
  private static final Logger LOG = Logger.getInstance("#com.intellij.refactoring.inline.InlineToAnonymousClassProcessor");

  private PsiClass myClass;
  private final PsiCall myCallToInline;
  private boolean myInlineThisOnly;

  protected InlineToAnonymousClassProcessor(Project project, PsiClass psiClass, final PsiCall callToInline, boolean inlineThisOnly) {
    super(project);
    myClass = psiClass;
    myCallToInline = callToInline;
    myInlineThisOnly = inlineThisOnly;
  }

  protected UsageViewDescriptor createUsageViewDescriptor(UsageInfo[] usages) {
    return new InlineViewDescriptor(myClass);
  }

  @NotNull
  protected UsageInfo[] findUsages() {
    if (myInlineThisOnly) {
      return new UsageInfo[] { new UsageInfo(myCallToInline) };
    }
    final Collection<PsiReference> refCollection = ReferencesSearch.search(myClass).findAll();
    Set<UsageInfo> usages = new HashSet<UsageInfo>();
    for (PsiReference reference : refCollection) {
      usages.add(new UsageInfo(reference.getElement()));
    }

    return usages.toArray(new UsageInfo[usages.size()]);
  }

  protected void refreshElements(PsiElement[] elements) {
    //To change body of implemented methods use File | Settings | File Templates.
  }

  protected boolean preprocessUsages(final Ref<UsageInfo[]> refUsages) {
    String s = getPreprocessUsagesMessage(refUsages);
    if (s != null) {
      CommonRefactoringUtil.showErrorMessage(RefactoringBundle.message("inline.to.anonymous.refactoring"), s, null, myClass.getProject());
      return false;
    }
    ArrayList<String> conflicts = getConflicts(refUsages);
    if (!conflicts.isEmpty()) {
      ConflictsDialog dialog = new ConflictsDialog(myProject, conflicts);
      dialog.show();
      if (!dialog.isOK()) {
        return false;
      }
    }
    return super.preprocessUsages(refUsages);
  }

  public ArrayList<String> getConflicts(final Ref<UsageInfo[]> refUsages) {
    UsageInfo[] usages = refUsages.get();
    ArrayList<String> result = new ArrayList<String>();
    ReferencedElementsCollector collector = new ReferencedElementsCollector() {
      protected void checkAddMember(final PsiMember member) {
        if (member.getContainingClass() != myClass) {
          super.checkAddMember(member);
        }
      }
    };
    InlineMethodProcessor.addInaccessibleMemberConflicts(myClass, usages, collector, result);
    return result;
  }

  protected void performRefactoring(UsageInfo[] usages) {
    PsiClassType superType = getSuperType();

    List<PsiElement> elementsToDelete = new ArrayList<PsiElement>();
    for(UsageInfo info: usages) {
      final PsiElement element = info.getElement();
      if (element instanceof PsiNewExpression) {
        replaceNewOrType((PsiNewExpression)element, superType);
      }
      else if (element.getParent() instanceof PsiNewExpression) {
        replaceNewOrType((PsiNewExpression) element.getParent(), superType);
      }
      else {
        PsiImportStatement statement = PsiTreeUtil.getParentOfType(element, PsiImportStatement.class);
        if (statement != null && !myInlineThisOnly) {
          elementsToDelete.add(statement);
        }
        else {
          PsiTypeElement typeElement = PsiTreeUtil.getParentOfType(element, PsiTypeElement.class);
          if (typeElement != null) {
            replaceWithSuperType(typeElement, superType);
          }
        }
      }
    }

    for(PsiElement element: elementsToDelete) {
      try {
        if (element.isValid()) {
          element.delete();
        }
      }
      catch (IncorrectOperationException e) {
        LOG.error(e);
      }
    }
    if (!myInlineThisOnly) {
      try {
        myClass.delete();
      }
      catch(IncorrectOperationException e) {
        LOG.error(e);
      }
    }
  }

  private void replaceNewOrType(final PsiNewExpression psiNewExpression, final PsiClassType superType) {
    try {
      if (psiNewExpression.getArrayDimensions().length == 0) {
        new InlineToAnonymousConstructorProcessor(myClass, psiNewExpression, superType).run();
      }
      else {
        PsiJavaCodeReferenceElement element = myClass.getManager().getElementFactory().createClassReferenceElement(superType.resolve());
        psiNewExpression.getClassReference().replace(element);        
      }
    }
    catch (IncorrectOperationException e) {
      LOG.error(e);
    }
  }

  private void replaceWithSuperType(final PsiTypeElement typeElement, final PsiClassType superType) {
    PsiElementFactory factory = myClass.getManager().getElementFactory();
    PsiClassType psiType = (PsiClassType) typeElement.getType();
    PsiClassType.ClassResolveResult classResolveResult = psiType.resolveGenerics();
    PsiType substType = classResolveResult.getSubstitutor().substitute(superType);
    assert classResolveResult.getElement() == myClass;
    try {
      typeElement.replace(factory.createTypeElement(substType));
    }
    catch(IncorrectOperationException e) {
      LOG.error(e);
    }
  }

  private PsiClassType getSuperType() {
    PsiElementFactory factory = myClass.getManager().getElementFactory();

    PsiClassType superType;
    final PsiClass[] interfaces = myClass.getInterfaces();
    if (interfaces.length > 0) {
      PsiClassType[] interfaceTypes = myClass.getImplementsListTypes();
      assert interfaces.length == 1;
      assert interfaceTypes.length == 1;
      superType = interfaceTypes [0];
    }
    else {
      PsiClass superClass = myClass.getSuperClass();
      PsiClassType[] classTypes = myClass.getExtendsListTypes();
      if (classTypes.length > 0) {
        superType = classTypes [0];
      }
      else {
        superType = factory.createType(superClass);
      }
    }
    return superType;
  }

  protected String getCommandName() {
    return "Inline class " + myClass;
  }

  @Nullable
  public String getPreprocessUsagesMessage(final Ref<UsageInfo[]> refUsages) {
    final UsageInfo[] usages = refUsages.get();
    for(UsageInfo usage: usages) {
      final PsiElement element = usage.getElement();
      final PsiElement parentElement = element.getParent();
      if (parentElement != null && parentElement.getParent() instanceof PsiClassObjectAccessExpression) {
        return "Class cannot be inlined because it has usages of its class literal";
      }
      if (parentElement instanceof PsiNewExpression) {
        final PsiNewExpression newExpression = (PsiNewExpression)parentElement;
        final PsiMethod[] constructors = myClass.getConstructors();
        if (constructors.length == 0) {
          PsiExpressionList newArgumentList = newExpression.getArgumentList();
          if (newArgumentList != null && newArgumentList.getExpressions().length > 0) {
            return "Class cannot be inlined because a call to its constructor is unresolved";
          }
        }
        else {
          final JavaResolveResult resolveResult = newExpression.resolveMethodGenerics();
          if (!resolveResult.isValidResult()) {
            return "Class cannot be inlined because a call to its constructor is unresolved";
          }
        }
      }
    }
    return null;
  }

}
