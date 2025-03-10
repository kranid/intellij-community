// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.module;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.roots.LanguageLevelModuleExtension;
import com.intellij.openapi.roots.LanguageLevelProjectExtension;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.pom.java.LanguageLevel;
import com.intellij.psi.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.ref.Reference;
import java.lang.ref.SoftReference;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;

import static com.intellij.reference.SoftReference.dereference;

public final class LanguageLevelUtil {
  /**
   * Returns explicitly specified custom language level for {@code module}, or {@code null} if the module uses 'Project default' language level
   */
  public static @Nullable LanguageLevel getCustomLanguageLevel(@NotNull Module module) {
    LanguageLevelModuleExtension moduleExtension = ModuleRootManager.getInstance(module).getModuleExtension(LanguageLevelModuleExtension.class);
    return moduleExtension != null ? moduleExtension.getLanguageLevel() : null;
  }

  @NotNull
  public static LanguageLevel getEffectiveLanguageLevel(@NotNull final Module module) {
    ApplicationManager.getApplication().assertReadAccessAllowed();
    LanguageLevel level = getCustomLanguageLevel(module);
    if (level != null) return level;
    return LanguageLevelProjectExtension.getInstance(module.getProject()).getLanguageLevel();
  }

  private static final Map<LanguageLevel, Reference<Set<String>>> ourForbiddenAPI = new EnumMap<>(LanguageLevel.class);

  public static String getJdkName(LanguageLevel languageLevel) {
    final String presentableText = languageLevel.getPresentableText();
    return presentableText.substring(0, presentableText.indexOf(' '));
  }

  /**
   * @param api The language level to get the next from.
   * @return Next {@link LanguageLevel} that is not in preview.
   */
  public static @Nullable LanguageLevel getNextLanguageLevel(@NotNull LanguageLevel api) {
    final LanguageLevel[] levels = LanguageLevel.values();
    final int currentLevelId = api.ordinal();
    for (int i = currentLevelId + 1; i < levels.length; i++) {
      final LanguageLevel level = levels[i];
      if (!level.isPreview()) return level;
    }
    return null;
  }

  private static final Map<LanguageLevel, String> ourPresentableShortMessage = new EnumMap<>(LanguageLevel.class);

  static {
    ourPresentableShortMessage.put(LanguageLevel.JDK_1_3, "1.4");
    ourPresentableShortMessage.put(LanguageLevel.JDK_1_4, "1.5");
    ourPresentableShortMessage.put(LanguageLevel.JDK_1_5, "1.6");
    ourPresentableShortMessage.put(LanguageLevel.JDK_1_6, "1.7");
    ourPresentableShortMessage.put(LanguageLevel.JDK_1_7, "1.8");
    ourPresentableShortMessage.put(LanguageLevel.JDK_1_8, "9");
    ourPresentableShortMessage.put(LanguageLevel.JDK_1_9, "10");
    ourPresentableShortMessage.put(LanguageLevel.JDK_10, "11");
    ourPresentableShortMessage.put(LanguageLevel.JDK_11, "12");
    ourPresentableShortMessage.put(LanguageLevel.JDK_12, "13");
    ourPresentableShortMessage.put(LanguageLevel.JDK_13, "14");
    ourPresentableShortMessage.put(LanguageLevel.JDK_14, "15");
    ourPresentableShortMessage.put(LanguageLevel.JDK_15, "16");
    ourPresentableShortMessage.put(LanguageLevel.JDK_16, "17");
    ourPresentableShortMessage.put(LanguageLevel.JDK_17, "18");
    ourPresentableShortMessage.put(LanguageLevel.JDK_18, "19");
    ourPresentableShortMessage.put(LanguageLevel.JDK_19, "20");
    ourPresentableShortMessage.put(LanguageLevel.JDK_20, "21");
    ourPresentableShortMessage.put(LanguageLevel.JDK_20_PREVIEW, "21");
    ourPresentableShortMessage.put(LanguageLevel.JDK_21, "22");
    ourPresentableShortMessage.put(LanguageLevel.JDK_21_PREVIEW, "22");
  }

  @Nullable
  public static String getShortMessage(@NotNull LanguageLevel languageLevel) {
    return ourPresentableShortMessage.get(languageLevel);
  }

  /**
   * For performance reasons the forbidden API is pre-generated.
   * @see com.intellij.codeInspection.tests.JavaApiUsageGenerator
   */
  @Nullable
  private static Set<String> getForbiddenApi(@NotNull LanguageLevel languageLevel) {
    if (!ourPresentableShortMessage.containsKey(languageLevel)) return null;
    Reference<Set<String>> ref = ourForbiddenAPI.get(languageLevel);
    Set<String> result = dereference(ref);
    if (result == null) {
      String fileName = "api" + getShortMessage(languageLevel) + ".txt";
      URL resource = LanguageLevelUtil.class.getResource(fileName);
      if (resource != null) {
        result = loadSignatureList(resource);
      } else {
        Logger.getInstance(LanguageLevelUtil.class).warn("File not found: " + fileName);
        result = Collections.emptySet();
      }
      ourForbiddenAPI.put(languageLevel, new SoftReference<>(result));
    }
    return result;
  }

  /**
   * @param member The {@link PsiMember} to get the language level from
   * @param languageLevel The effective language level
   * @return The last incompatible language level for a {@link PsiMember} as annotated by the @since javadoc or null if it is unknown.
   * For example, if a method is annotated as @since 9 this method will return {@link LanguageLevel#JDK_1_8}.
   */
  public static @Nullable LanguageLevel getLastIncompatibleLanguageLevel(@NotNull PsiMember member, @NotNull LanguageLevel languageLevel) {
    if (member instanceof PsiAnonymousClass) return null;
    PsiClass containingClass = member.getContainingClass();
    if (containingClass instanceof PsiAnonymousClass) return null;
    if (member instanceof PsiClass && !(member.getParent() instanceof PsiClass || member.getParent() instanceof PsiFile)) return null;
    String signature = getSignature(member);
    if (signature == null) return null;
    LanguageLevel lastLanguageLevel = getLastIncompatibleLanguageLevelForSignature(signature, languageLevel);
    if (lastLanguageLevel != null) {
      if (member instanceof PsiMethod && !((PsiMethod)member).isConstructor()) {
        LanguageLevel lowestSuperLanguageLevel = lastLanguageLevel;
        for (PsiMethod method : ((PsiMethod)member).findSuperMethods()) {
          String superSignature = getSignature(method);
          if (superSignature == null) return null;
          LanguageLevel lastSuperLanguageLevel = getLastIncompatibleLanguageLevelForSignature(superSignature, languageLevel);
          if (lastSuperLanguageLevel == null) return null;
          if (lastSuperLanguageLevel.isLessThan(lowestSuperLanguageLevel)) {
            lowestSuperLanguageLevel = lastSuperLanguageLevel;
          }
        }
        return lowestSuperLanguageLevel;
      }
    }
    return lastLanguageLevel;
  }

  private static LanguageLevel getLastIncompatibleLanguageLevelForSignature(@NotNull String signature, @NotNull LanguageLevel languageLevel) {
    Set<String> forbiddenApi = getForbiddenApi(languageLevel);
    if (forbiddenApi == null) return null;
    if (forbiddenApi.contains(signature)) return languageLevel;
    if (languageLevel.compareTo(LanguageLevel.HIGHEST) == 0) return null;
    LanguageLevel nextLanguageLevel = LanguageLevel.values()[languageLevel.ordinal() + 1];
    return getLastIncompatibleLanguageLevelForSignature(signature, nextLanguageLevel);
  }

  public static Set<String> loadSignatureList(@NotNull URL resource) {
    try (BufferedReader reader = new BufferedReader(new InputStreamReader(resource.openStream(), StandardCharsets.UTF_8))) {
      return new HashSet<>(FileUtil.loadLines(reader));
    }
    catch (IOException ex) {
      Logger.getInstance(LanguageLevelUtil.class).warn("cannot load: " + resource.getFile(), ex);
      return Collections.emptySet();
    }
  }

  /**
   * For serialization of forbidden api.
   */
  @Nullable
  public static String getSignature(@Nullable PsiMember member) {
    if (member instanceof PsiClass) {
      return ((PsiClass)member).getQualifiedName();
    }
    if (member instanceof PsiField) {
      String containingClass = getSignature(member.getContainingClass());
      return containingClass == null ? null : containingClass + "#" + member.getName();
    }
    if (member instanceof PsiMethod method) {
      String containingClass = getSignature(member.getContainingClass());
      if (containingClass == null) return null;

      StringBuilder buf = new StringBuilder();
      buf.append(containingClass);
      buf.append('#');
      buf.append(method.getName());
      buf.append('(');
      for (PsiType type : method.getSignature(PsiSubstitutor.EMPTY).getParameterTypes()) {
        buf.append(type.getCanonicalText());
        buf.append(";");
      }
      buf.append(')');
      return buf.toString();
    }
    return null;
  }

}
