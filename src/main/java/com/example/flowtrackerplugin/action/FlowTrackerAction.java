package com.example.flowtrackerplugin.action;

import com.intellij.execution.ui.ConsoleView;
import com.intellij.execution.ui.ConsoleViewContentType;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.LangDataKeys;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.markup.TextAttributes;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiExpression;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.PsiReference;
import com.intellij.psi.PsiReferenceExpression;
import com.intellij.psi.PsiSubstitutor;
import com.intellij.psi.PsiType;
import com.intellij.psi.search.searches.ReferencesSearch;
import com.intellij.psi.util.MethodSignature;
import com.intellij.psi.util.PsiTreeUtil;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;

import java.awt.Color;
import java.util.Collection;
import java.util.ListIterator;
import java.util.Stack;

import static com.intellij.icons.AllIcons.Actions.DiagramDiff;
import static java.util.AbstractMap.SimpleEntry;

public class FlowTrackerAction extends AnAction {
    private static final Logger logger = LogManager.getLogger(FlowTrackerAction.class.getName());
    private ConsoleView consoleView;
    private final ConsoleViewContentType classNameContentType = getContentType(ConsoleColor.BRIGHT_CYAN);
    private final ConsoleViewContentType methodNameContentType = getContentType(ConsoleColor.GREEN);
    private final ConsoleViewContentType lineNumberContentType = getContentType(ConsoleColor.ORANGE);
    private final ConsoleViewContentType separatorContentType = getContentType(ConsoleColor.RED);
    private final ConsoleViewContentType emptyContentType = getContentType(ConsoleColor.RED);

    public FlowTrackerAction() {
        super();
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent event) {
        Project project = event.getProject();

        // Initialize the ConsoleView
        consoleView = FlowTrackerToolWindowFactory.validateToolWindowIntegrity(project);

        PsiElement psiElement = event.getData(LangDataKeys.PSI_ELEMENT);

        // Search for references to the method
        if (psiElement instanceof PsiMethod) {
            analyzeReferences(project, (PsiMethod) psiElement, null, new Stack<>());
        }
    }

    /**
     * The goal of the algorithm is to analyze a Java codebase and identify all the methods that are called exclusively from non-test methods, i.e., methods that are not called by test code.
     * To accomplish this, the algorithm traverses the call hierarchy of each non-test method in the codebase and identifies all the methods that it calls. If a called method is not a test
     * method itself and is only called by test methods or is not called by any other method, it is considered a leaf node in the call hierarchy. The algorithm then prints out the full call
     * stack from the leaf node back to the original non-test method. This allows developers to identify and potentially refactor methods that are only used by non-test code, which may help
     * to improve code maintainability and reduce unnecessary code complexity.
     * @param project
     * @param method
     * @param methodReference
     * @param visitedMethods
     * @return
     */
    private boolean analyzeReferences(Project project, PsiMethod method, PsiElement methodReference, Stack<SimpleEntry<PsiMethod, PsiElement>> visitedMethods) {

        if (!isInProjectSources(method) || isTestClass(method.getContainingClass()) || isTestMethod(method) || method.isConstructor()) {
            return true;
        }

        visitedMethods.push(new SimpleEntry<>(method, methodReference));

        // Search for references to the method
        Collection<PsiReference> references = ReferencesSearch.search(method).findAll();

        // Search for superMethods. I have to do this because ReferencesSearch works on the AST level, and the super method is not part of the current AST being searched
        PsiMethod[] superMethods = method.findSuperMethods();

        for (PsiMethod superMethod : superMethods) {
            references.addAll(ReferencesSearch.search(superMethod).findAll());
        }

        if (belongsToAbstractClass(method)) {
            // If the method belongs to an abstract class, filter the references.
            // Only retain references that invoke the method from a class that overrides the method
            // and that has been previously analyzed (exists in the visitedMethods stack).

            // Filter the references based on the class of the immediately previously overridden method.
            references = filterByImmediateOverrideMethodContainingClass(references, visitedMethods);
        }

        boolean isFinalNode = true;

        for (PsiReference reference : references) {
            PsiElement referenceElement = reference.getElement();

            // Get the parent method for the current reference
            PsiMethod parentMethod = PsiTreeUtil.getParentOfType(referenceElement, PsiMethod.class);

            if (parentMethod == null || parentMethod.equals(method)) // This is to avoid any non-method references and a recursive call like with a Decorator Pattern
                continue;

            boolean parentIsTestOrConstructorNode = analyzeReferences(project, parentMethod, referenceElement, visitedMethods);

            isFinalNode = isFinalNode && parentIsTestOrConstructorNode;
        }

        // Print the complete path
        if (isFinalNode && visitedMethods.size() > 1) {
            printStack(project, visitedMethods);
        }

        // Go back one level
        visitedMethods.pop();
        return false;
    }

    private boolean isInProjectSources(PsiMethod psiMethod) {
        return ProjectRootManager.getInstance(psiMethod.getProject()).getFileIndex().isInContent(psiMethod.getContainingFile().getVirtualFile());
    }

    private boolean isTestClass(PsiClass psiClass) {
        return isTestDefinition(psiClass.getName());
    }

    private boolean isTestMethod(PsiMethod psiMethod) {
        return isTestDefinition(psiMethod.getName());
    }

    private boolean isTestDefinition(String definition) {
        return definition.toLowerCase().endsWith("test");
    }

    private boolean belongsToAbstractClass(PsiMethod method) {
        return method.getContainingClass().hasModifierProperty(PsiModifier.ABSTRACT);
    }

    /**
     * Filters the provided references based on whether they match the class of the immediate
     * overriding method found in the stack of visited methods.
     * <p>
     * This method first identifies an overriding method from the stack of visited methods
     * using the {@code retrieveOverridingMethodForCurrentFlow} method. If such a method is found,
     * it extracts its containing class's fully qualified name. The provided references are then
     * filtered based on whether they match this class.
     * </p>
     *
     * @param references A collection of {@code PsiReference} to be filtered.
     * @param visitedMethods A stack of visited methods, paired with a relevant PsiElement.
     * @return A filtered collection of {@code PsiReference} that belong to the class of the
     *         identified overriding method. If no such method is found, the original references
     *         collection is returned unaltered.
     */
    private Collection<PsiReference> filterByImmediateOverrideMethodContainingClass(Collection<PsiReference> references,  Stack<SimpleEntry<PsiMethod, PsiElement>> visitedMethods) {
        PsiMethod matchingMethod = retrieveOverridingMethodForCurrentFlow(visitedMethods);

        if (matchingMethod == null)
            return references;

        String matchingClass = matchingMethod.getContainingClass().getQualifiedName();

        return references.stream().filter(reference -> {
            String fieldClassName = resolveFieldReferenceClassName(reference);
            return fieldClassName != null && fieldClassName.equals(matchingClass);
        }).toList();
    }

    /**
     * Retrieves the overriding method from a stack of visited methods that directly overrides
     * a method in its abstract superclass.
     * <p>
     * This method traverses the stack of visited methods, and for each method, checks if it is
     * an overriding method. If so, it then navigates the hierarchy of its containing class to
     * locate an abstract superclass. If an abstract superclass is found, the method then compares
     * the signatures of all methods in the superclass to identify if the current method overrides
     * any of them. If a match is found, that current method is returned.
     * </p>
     *
     * @param visitedMethods A stack of visited methods, paired with a relevant PsiElement.
     * @return The overriding method that overrides a method from an abstract superclass if found;
     *         otherwise, returns null.
     */
    private PsiMethod retrieveOverridingMethodForCurrentFlow(Stack<SimpleEntry<PsiMethod, PsiElement>> visitedMethods) {
        for(int i = visitedMethods.size() - 1; i >= 0; i--) {
            PsiMethod currentMethod = visitedMethods.get(i).getKey();
            if (isMethodOverriding(currentMethod)) {
                PsiClass containingClass = currentMethod.getContainingClass();
                PsiClass superClass = containingClass.getSuperClass();

                while (superClass != null) {
                    if (superClass.isInterface()) { // Skip interface
                        superClass = superClass.getSuperClass();
                        continue;
                    }

                    if (superClass.hasModifierProperty(PsiModifier.ABSTRACT)) {
                        for (PsiMethod method : superClass.getAllMethods()) {
                            MethodSignature methodSignature = method.getSignature(PsiSubstitutor.EMPTY);
                            MethodSignature overridingMethodSignature = currentMethod.getSignature(PsiSubstitutor.EMPTY);
                            if (methodSignature.equals(overridingMethodSignature)) {
                                return currentMethod;
                            }
                        }
                    }
                }
                return null;
            }
        }
        return null;
    }

    private boolean isMethodOverriding(PsiMethod method) {
        PsiMethod[] superMethods = method.findSuperMethods();
        return superMethods.length > 0;
    }

    /**
     * Resolves and retrieves the fully qualified class name of the type of field referenced by a given {@code PsiReference}.
     * <p>
     * The method checks if the provided reference is a {@code PsiReferenceExpression}. If so, it inspects its qualifier to
     * determine if it corresponds to a {@code PsiField}. If both conditions are satisfied, the canonical text of the field's
     * type (essentially its fully qualified class name) is returned.
     * </p>
     *
     * @param reference A {@code PsiReference} whose associated field's class name needs to be determined.
     * @return The fully qualified class name of the field associated with the reference, or {@code null} if
     *         the reference does not correspond to a field or if the class name cannot be resolved.
     */
    private String resolveFieldReferenceClassName(PsiReference reference) {
        if (reference instanceof PsiReferenceExpression) {
            PsiReferenceExpression referenceExpression = (PsiReferenceExpression) reference;
            PsiExpression qualifierExpression = referenceExpression.getQualifierExpression();
            if (qualifierExpression instanceof PsiReferenceExpression) {
                PsiElement resolvedElement = ((PsiReferenceExpression) qualifierExpression).resolve();
                if (resolvedElement instanceof PsiField) {
                    PsiField field = (PsiField) resolvedElement;
                    PsiType fieldType = field.getType();
                    return fieldType.getCanonicalText();
                }
            }
        }
        return null;
    }

    private void printStack(Project project, Stack<SimpleEntry<PsiMethod, PsiElement>> visitedMethods) {
        int indentation = 0;

        ListIterator<SimpleEntry<PsiMethod, PsiElement>> listIterator = visitedMethods.listIterator(visitedMethods.size());

        StringBuilder stringBuilder = new StringBuilder();

        while (listIterator.hasPrevious()) {
            stringBuilder.append(buildMessage(project, listIterator.previous(), indentation));
            indentation += 4; // The amount of spaces to be used on each line
        }

        // This is to make the code more readable
        consoleView.print("\n", emptyContentType);

        // Print the result into console
        logger.info(stringBuilder.toString());
    }

    private String buildMessage(Project project, SimpleEntry<PsiMethod, PsiElement> entry, int indentation) {
        PsiMethod method = entry.getKey();

        // Get class and method names
        String methodName = method.getName();
        String className = method.getContainingClass().getName();

        // Calculate line number of method definition
        PsiElement methodReference = entry.getValue();
        int lineNumber = calculateLineNumber(project, methodReference == null ? method : methodReference);

        String padding = new String(new char[indentation]).replace('\0', ' '); // I have to do this because the %0s format breaks

        // Print the message into ConsoleView
        printMessage(padding, className, methodName, String.valueOf(lineNumber));

        String format = "%s" + ConsoleColor.BRIGHT_CYAN.ansiCode + "%s" + ConsoleColor.RED.ansiCode + "." +
                ConsoleColor.GREEN.ansiCode + "%s" + ConsoleColor.RED.ansiCode + ":" +
                ConsoleColor.ORANGE.ansiCode + "%d" + ConsoleColor.RESET.ansiCode + "%n";

        return String.format(format, padding, className, methodName, lineNumber);
    }

    private void printMessage(String padding, String className, String methodName, String lineNumber) {
        consoleView.print(padding, emptyContentType);
        consoleView.print(className, classNameContentType);
        consoleView.print(".", separatorContentType);
        consoleView.print(methodName, methodNameContentType);
        consoleView.print(":", separatorContentType);
        consoleView.print(String.valueOf(lineNumber), lineNumberContentType);
        consoleView.print("\n", emptyContentType);
    }

    private int calculateLineNumber(Project project, PsiElement element) {
        PsiFile file = element.getContainingFile();
        Document document = PsiDocumentManager.getInstance(project).getDocument(file);
        return document.getLineNumber(element.getTextRange().getStartOffset()) + 1;
    }

    private ConsoleViewContentType getContentType(ConsoleColor consoleColor) {
        Color color = createColor(consoleColor.rgbCodes);
        return createContentType(consoleColor.name(), color);
    }

    private Color createColor(int[] rgbColor) {
        float[] HSBValues = Color.RGBtoHSB(rgbColor[0], rgbColor[1], rgbColor[2], null);
        return Color.getHSBColor(HSBValues[0], HSBValues[1], HSBValues[2]);
    }

    private ConsoleViewContentType createContentType(String name, Color color) {
        TextAttributes textAttributes = new TextAttributes();
        textAttributes.setForegroundColor(color);
        return new ConsoleViewContentType(name, textAttributes);
    }

    @Override
    public void update(@NotNull AnActionEvent event) {
        // Set the availability based on whether an editor is open
        Editor editor = event.getData(CommonDataKeys.EDITOR);
        boolean isMethodSelection = event.getData(CommonDataKeys.PSI_ELEMENT) instanceof PsiMethod;
        event.getPresentation().setEnabledAndVisible(editor != null && isMethodSelection);
        event.getPresentation().setIcon(DiagramDiff);
    }

    private enum ConsoleColor {
        RESET("\u001B[0m", new int[]{201, 72, 54}),
        BRIGHT_CYAN("\u001B[1;36m", new int[]{14, 185, 196}),
        GREEN("\u001B[1;32m", new int[]{105, 176, 48}),
        ORANGE("\u001B[0;33m", new int[]{163, 136, 47}),
        RED("\u001B[31m", new int[]{201, 72, 54});

        private final String ansiCode;
        private final int[] rgbCodes;

        ConsoleColor(String ansiCode, int[] rgbCodes) {
            this.ansiCode = ansiCode;
            this.rgbCodes = rgbCodes;
        }
    }
}
