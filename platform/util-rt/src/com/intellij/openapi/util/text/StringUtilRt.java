/*
 * Copyright 2000-2016 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.intellij.openapi.util.text;

import com.intellij.util.Function;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * Stripped-down version of {@code com.intellij.openapi.util.text.StringUtil}.
 * Intended to use by external (out-of-IDE-process) runners and helpers so it should not contain any library dependencies.
 *
 * @since 12.0
 */
@SuppressWarnings("UtilityClassWithoutPrivateConstructor")
public class StringUtilRt {
  @Contract(pure = true)
  public static boolean charsEqualIgnoreCase(char a, char b) {
    return a == b || toUpperCase(a) == toUpperCase(b) || toLowerCase(a) == toLowerCase(b);
  }

  @NotNull
  @Contract(pure = true)
  public static CharSequence toUpperCase(@NotNull CharSequence s) {
    StringBuilder answer = null;

    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      char upCased = toUpperCase(c);
      if (answer == null && upCased != c) {
        answer = new StringBuilder(s.length());
        answer.append(s.subSequence(0, i));
      }

      if (answer != null) {
        answer.append(upCased);
      }
    }

    return answer == null ? s : answer;
  }

  @Contract(pure = true)
  public static char toUpperCase(char a) {
    if (a < 'a') {
      return a;
    }
    if (a <= 'z') {
      return (char)(a + ('A' - 'a'));
    }
    return Character.toUpperCase(a);
  }

  @Contract(pure = true)
  public static char toLowerCase(char a) {
    if (a < 'A' || a >= 'a' && a <= 'z') {
      return a;
    }

    if (a <= 'Z') {
      return (char)(a + ('a' - 'A'));
    }

    return Character.toLowerCase(a);
  }

  /**
   * Converts line separators to {@code "\n"}
   */
  @NotNull
  @Contract(pure = true)
  public static String convertLineSeparators(@NotNull String text) {
    return convertLineSeparators(text, false);
  }

  @NotNull
  @Contract(pure = true)
  public static String convertLineSeparators(@NotNull String text, boolean keepCarriageReturn) {
    return convertLineSeparators(text, "\n", null, keepCarriageReturn);
  }

  @NotNull
  @Contract(pure = true)
  public static String convertLineSeparators(@NotNull String text, @NotNull String newSeparator) {
    return convertLineSeparators(text, newSeparator, null);
  }

  @NotNull
  @Contract(pure = true)
  public static CharSequence convertLineSeparators(@NotNull CharSequence text, @NotNull String newSeparator) {
    return unifyLineSeparators(text, newSeparator, null, false);
  }

  @NotNull
  public static String convertLineSeparators(@NotNull String text, @NotNull String newSeparator, @Nullable int[] offsetsToKeep) {
    return convertLineSeparators(text, newSeparator, offsetsToKeep, false);
  }

  @NotNull
  public static String convertLineSeparators(@NotNull String text,
                                             @NotNull String newSeparator,
                                             @Nullable int[] offsetsToKeep,
                                             boolean keepCarriageReturn) {
    return unifyLineSeparators(text, newSeparator, offsetsToKeep, keepCarriageReturn).toString();
  }

  @NotNull
  private static CharSequence unifyLineSeparators(@NotNull CharSequence text,
                                                  @NotNull String newSeparator,
                                                  @Nullable int[] offsetsToKeep,
                                                  boolean keepCarriageReturn) {
    StringBuilder buffer = null;
    int intactLength = 0;
    final boolean newSeparatorIsSlashN = "\n".equals(newSeparator);
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (c == '\n') {
        if (!newSeparatorIsSlashN) {
          if (buffer == null) {
            buffer = new StringBuilder(text.length());
            buffer.append(text, 0, intactLength);
          }
          buffer.append(newSeparator);
          shiftOffsets(offsetsToKeep, buffer.length(), 1, newSeparator.length());
        }
        else if (buffer == null) {
          intactLength++;
        }
        else {
          buffer.append(c);
        }
      }
      else if (c == '\r') {
        boolean followedByLineFeed = i < text.length() - 1 && text.charAt(i + 1) == '\n';
        if (!followedByLineFeed && keepCarriageReturn) {
          if (buffer == null) {
            intactLength++;
          }
          else {
            buffer.append(c);
          }
          continue;
        }
        if (buffer == null) {
          buffer = new StringBuilder(text.length());
          buffer.append(text, 0, intactLength);
        }
        buffer.append(newSeparator);
        if (followedByLineFeed) {
          //noinspection AssignmentToForLoopParameter
          i++;
          shiftOffsets(offsetsToKeep, buffer.length(), 2, newSeparator.length());
        }
        else {
          shiftOffsets(offsetsToKeep, buffer.length(), 1, newSeparator.length());
        }
      }
      else {
        if (buffer == null) {
          intactLength++;
        }
        else {
          buffer.append(c);
        }
      }
    }
    return buffer == null ? text : buffer;
  }

  private static void shiftOffsets(int[] offsets, int changeOffset, int oldLength, int newLength) {
    if (offsets == null) return;
    int shift = newLength - oldLength;
    if (shift == 0) return;
    for (int i = 0; i < offsets.length; i++) {
      int offset = offsets[i];
      if (offset >= changeOffset + oldLength) {
        offsets[i] += shift;
      }
    }
  }

  @Contract(pure = true)
  public static int parseInt(@Nullable String string, final int defaultValue) {
    if (string == null) {
      return defaultValue;
    }

    try {
      return Integer.parseInt(string);
    }
    catch (Exception e) {
      return defaultValue;
    }
  }

  @Contract(pure = true)
  public static long parseLong(@Nullable String string, long defaultValue) {
    if (string == null) {
      return defaultValue;
    }
    try {
      return Long.parseLong(string);
    }
    catch (Exception e) {
      return defaultValue;
    }
  }

  @Contract(pure = true)
  public static double parseDouble(final String string, final double defaultValue) {
    try {
      return Double.parseDouble(string);
    }
    catch (Exception e) {
      return defaultValue;
    }
  }

  @Contract(pure = true)
  public static boolean parseBoolean(final String string, final boolean defaultValue) {
    try {
      return Boolean.parseBoolean(string);
    }
    catch (Exception e) {
      return defaultValue;
    }
  }

  @Contract(pure = true)
  static <E extends Enum<E>> E parseEnum(@NotNull String string, E defaultValue, @NotNull Class<E> clazz) {
    try {
      return Enum.valueOf(clazz, string);
    }
    catch (Exception e) {
      return defaultValue;
    }
  }

  @NotNull
  @Contract(pure = true)
  public static String getShortName(@NotNull Class aClass) {
    return getShortName(aClass.getName());
  }

  @NotNull
  @Contract(pure = true)
  public static String getShortName(@NotNull String fqName) {
    return getShortName(fqName, '.');
  }

  @NotNull
  @Contract(pure = true)
  public static String getShortName(@NotNull String fqName, char separator) {
    int lastPointIdx = fqName.lastIndexOf(separator);
    if (lastPointIdx >= 0) {
      return fqName.substring(lastPointIdx + 1);
    }
    return fqName;
  }

  @Contract(pure = true)
  public static boolean endsWithChar(@Nullable CharSequence s, char suffix) {
    return s != null && s.length() != 0 && s.charAt(s.length() - 1) == suffix;
  }

  @Contract(pure = true)
  public static boolean startsWithIgnoreCase(@NonNls @NotNull String str, @NonNls @NotNull String prefix) {
    final int stringLength = str.length();
    final int prefixLength = prefix.length();
    return stringLength >= prefixLength && str.regionMatches(true, 0, prefix, 0, prefixLength);
  }

  @Contract(pure = true)
  public static boolean endsWithIgnoreCase(@NonNls @NotNull CharSequence text, @NonNls @NotNull CharSequence suffix) {
    int l1 = text.length();
    int l2 = suffix.length();
    if (l1 < l2) return false;

    for (int i = l1 - 1; i >= l1 - l2; i--) {
      if (!charsEqualIgnoreCase(text.charAt(i), suffix.charAt(i + l2 - l1))) {
        return false;
      }
    }

    return true;
  }

  /**
   * Allows to retrieve index of last occurrence of the given symbols at {@code [start; end)} sub-sequence of the given text.
   *
   * @param s     target text
   * @param c     target symbol which last occurrence we want to check
   * @param start start offset of the target text (inclusive)
   * @param end   end offset of the target text (exclusive)
   * @return index of the last occurrence of the given symbol at the target sub-sequence of the given text if any;
   * {@code -1} otherwise
   */
  @Contract(pure = true)
  public static int lastIndexOf(@NotNull CharSequence s, char c, int start, int end) {
    start = Math.max(start, 0);
    for (int i = Math.min(end, s.length()) - 1; i >= start; i--) {
      if (s.charAt(i) == c) return i;
    }
    return -1;
  }

  @Contract(value = "null -> true",pure = true)
  public static boolean isEmpty(@Nullable CharSequence cs) {
    return cs == null || cs.length() == 0;
  }

  @Contract(value = "null -> true", pure = true)
  public static boolean isEmptyOrSpaces(@Nullable CharSequence s) {
    if (isEmpty(s)) {
      return true;
    }
    for (int i = 0; i < s.length(); i++) {
      if (s.charAt(i) > ' ') {
        return false;
      }
    }
    return true;
  }

  @NotNull
  @Contract(pure = true)
  public static <T> Function<T, String> createToStringFunction() {
    return new Function<T, String>() {
      public String fun(@NotNull T o) {
        return o.toString();
      }
    };
  }

  @NotNull
  @Contract(pure = true)
  public static String notNullize(@Nullable String s) {
    return notNullize(s, "");
  }

  @NotNull
  @Contract(pure = true)
  public static String notNullize(@Nullable String s, @NotNull String defaultValue) {
    return s == null ? defaultValue : s;
  }

  @NotNull
  @Contract(pure = true)
  public static <T> String join(@NotNull Collection<? extends T> items,
                                @NotNull Function<? super T, String> f,
                                @NotNull String separator) {
    if (items.isEmpty()) return "";
    if (items.size() == 1) return notNullize(f.fun(items.iterator().next()));
    return join((Iterable<? extends T>)items, f, separator);
  }

  @NotNull
  @Contract(pure = true)
  public static <T> String join(@NotNull Iterable<? extends T> items,
                                @NotNull Function<? super T, String> f,
                                @NotNull String separator) {
    final StringBuilder result = new StringBuilder();
    join(items, f, separator, result);
    return result.toString();
  }

  public static <T> void join(@NotNull Iterable<? extends T> items,
                              @NotNull Function<? super T, String> f,
                              @NotNull String separator,
                              @NotNull StringBuilder result) {
    boolean isFirst = true;
    for (T item : items) {
      String string = f.fun(item);
      if (string != null && string.length() > 0) {
        if (isFirst) {
          isFirst = false;
        } else {
          result.append(separator);
        }
        result.append(string);
      }
    }
  }

  @NotNull
  @Contract(pure = true)
  public static List<String> split(@NotNull String s, @NotNull String separator) {
    return split(s, separator, true);
  }
  @NotNull
  @Contract(pure = true)
  public static List<CharSequence> split(@NotNull CharSequence s, @NotNull CharSequence separator) {
    return split(s, separator, true, true);
  }

  @NotNull
  @Contract(pure = true)
  public static List<String> split(@NotNull String s, @NotNull String separator,
                                   boolean excludeSeparator) {
    return split(s, separator, excludeSeparator, true);
  }

  @NotNull
  @Contract(pure = true)
  @SuppressWarnings("unchecked")
  public static List<String> split(@NotNull String s, @NotNull String separator, boolean excludeSeparator, boolean excludeEmptyStrings) {
    return (List)split((CharSequence)s, separator, excludeSeparator, excludeEmptyStrings);
  }

  @NotNull
  @Contract(pure = true)
  public static List<CharSequence> split(@NotNull CharSequence s, @NotNull CharSequence separator, boolean excludeSeparator, boolean excludeEmptyStrings) {
    if (separator.length() == 0) {
      return Collections.singletonList(s);
    }
    List<CharSequence> result = new ArrayList<CharSequence>();
    int pos = 0;
    while (true) {
      int index = indexOf(s,separator, pos);
      if (index == -1) break;
      final int nextPos = index + separator.length();
      CharSequence token = s.subSequence(pos, excludeSeparator ? index : nextPos);
      if (token.length() != 0 || !excludeEmptyStrings) {
        result.add(token);
      }
      pos = nextPos;
    }
    if (pos < s.length() || !excludeEmptyStrings && pos == s.length()) {
      result.add(s.subSequence(pos, s.length()));
    }
    return result;
  }

  @NotNull
  @Contract(pure = true)
  public static List<String> splitHonorQuotes(@NotNull String s, char separator) {
    final List<String> result = new ArrayList<String>();
    final StringBuilder builder = new StringBuilder(s.length());
    boolean inQuotes = false;
    for (int i = 0; i < s.length(); i++) {
      final char c = s.charAt(i);
      if (c == separator && !inQuotes) {
        if (builder.length() > 0) {
          result.add(builder.toString());
          builder.setLength(0);
        }
        continue;
      }

      if ((c == '"' || c == '\'') && !(i > 0 && s.charAt(i - 1) == '\\')) {
        inQuotes = !inQuotes;
      }
      builder.append(c);
    }

    if (builder.length() > 0) {
      result.add(builder.toString());
    }
    return result;
  }

  @Contract(pure = true)
  public static int indexOf(@NotNull CharSequence s, char c) {
    return indexOf(s, c, 0, s.length());
  }

  @Contract(pure = true)
  public static int indexOf(@NotNull CharSequence s, char c, int start) {
    return indexOf(s, c, start, s.length());
  }

  @Contract(pure = true)
  public static int indexOf(@NotNull CharSequence s, char c, int start, int end) {
    end = Math.min(end, s.length());
    for (int i = Math.max(start, 0); i < end; i++) {
      if (s.charAt(i) == c) return i;
    }
    return -1;
  }

  @Contract(pure = true)
  public static boolean contains(@NotNull CharSequence sequence, @NotNull CharSequence infix) {
    return indexOf(sequence, infix) >= 0;
  }

  @Contract(pure = true)
  public static int indexOf(@NotNull CharSequence sequence, @NotNull CharSequence infix) {
    return indexOf(sequence, infix, 0);
  }

  @Contract(pure = true)
  public static int indexOf(@NotNull CharSequence sequence, @NotNull CharSequence infix, int start) {
    return indexOf(sequence, infix, start, sequence.length());
  }

  @Contract(pure = true)
  public static int indexOf(@NotNull CharSequence sequence, @NotNull CharSequence infix, int start, int end) {
    for (int i = start; i <= end - infix.length(); i++) {
      if (startsWith(sequence, i, infix)) {
        return i;
      }
    }
    return -1;
  }

  @Contract(pure = true)
  public static boolean startsWith(@NotNull CharSequence text, @NotNull CharSequence prefix) {
    int l1 = text.length();
    int l2 = prefix.length();
    if (l1 < l2) return false;

    for (int i = 0; i < l2; i++) {
      if (text.charAt(i) != prefix.charAt(i)) return false;
    }

    return true;
  }

  @Contract(pure = true)
  public static boolean startsWith(@NotNull CharSequence text, int startIndex, @NotNull CharSequence prefix) {
    int tl = text.length();
    if (startIndex < 0 || startIndex > tl) {
      throw new IllegalArgumentException("Index is out of bounds: " + startIndex + ", length: " + tl);
    }
    int l1 = tl - startIndex;
    int l2 = prefix.length();
    if (l1 < l2) return false;

    for (int i = 0; i < l2; i++) {
      if (text.charAt(i + startIndex) != prefix.charAt(i)) return false;
    }

    return true;
  }

  @Contract(pure = true)
  public static boolean endsWith(@NotNull CharSequence text, @NotNull CharSequence suffix) {
    int l1 = text.length();
    int l2 = suffix.length();
    if (l1 < l2) return false;

    for (int i = l1 - 1; i >= l1 - l2; i--) {
      if (text.charAt(i) != suffix.charAt(i + l2 - l1)) return false;
    }

    return true;
  }

  @NotNull
  @Contract(pure = true)
  public static String formatNumber(long number) {
    return formatNumber(number, "");
  }

  @NotNull
  @Contract(pure = true)
  public static String formatNumber(long number, @NotNull String unitSeparator) {
    return formatValue(number, null,
                       unitSeparator, new String[]{"", "K", "M", "G", "T", "P", "E"},
                       new long[]{1, 1000, 1000, 1000, 1000, 1000, 1000});
  }

  /**
   * Formats the specified file size as a string.
   *
   * @param fileSize the size to format.
   * @return the size formatted as a string.
   */
  @NotNull
  @Contract(pure = true)
  public static String formatFileSize(long fileSize) {
    return formatFileSize(fileSize, " ");
  }

  @NotNull
  @Contract(pure = true)
  public static String formatFileSize(long fileSize, @NotNull String unitSeparator) {
    return formatValue(fileSize, null,
                       unitSeparator, new String[]{"B", "KB", "MB", "GB", "TB", "PB", "EB"},
                       new long[]{1, 1024, 1024, 1024, 1024, 1024, 1024});
  }

  @NotNull
  @Contract(pure = true)
  public static String formatDuration(long duration) {
    return formatDuration(duration, " ");
  }

  @NotNull
  @Contract(pure = true)
  public static String formatDuration(long duration, @NotNull String unitSeparator) {
    return formatValue(duration, " ", unitSeparator,
                       new String[]{"ms", "s", "m", "h", "d", "mo", "yr", "c", "ml", "ep"},
                       new long[]{1, 1000, 60, 60, 24, 30, 12, 100, 10, 10000});
  }

  @NotNull
  private static String formatValue(long value,
                                    @Nullable String partSeparator, @NotNull String unitSeparator,
                                    @NotNull String[] units, @NotNull long[] multipliers) {
    if (units.length != multipliers.length) {
      throw new IllegalArgumentException(units.length + " != " + multipliers.length);
    }
    StringBuilder sb = new StringBuilder();
    long count = value;
    long remainder = 0;
    int i = 1;
    for (; i < units.length && count > 0; i++) {
      long multiplier = multipliers[i];
      if (count < multiplier) break;
      remainder = count % multiplier;
      count /= multiplier;
      if (partSeparator != null && (remainder != 0 || sb.length() > 0)) {
        if (units[i - 1].length() > 0) {
          sb.insert(0, units[i - 1]);
          sb.insert(0, unitSeparator);
        }
        sb.insert(0, remainder).insert(0, partSeparator);
      }
      else {
        remainder = Math.round(remainder * 100 / (double)multiplier);
        count += remainder / 100;
        remainder %= 100;
      }
    }
    if (partSeparator != null || remainder == 0) {
      if (units[i - 1].length() > 0) {
        sb.insert(0, units[i - 1]);
        sb.insert(0, unitSeparator);
      }
      sb.insert(0, count);
    }
    else if (remainder > 0) {
      sb.append(count).append(".").append(remainder / 10 == 0 ? "0" : "").append(remainder);
      if (units[i - 1].length() > 0) {
        sb.append(unitSeparator);
        sb.append(units[i - 1]);
      }
    }
    return sb.toString();
  }

  private static boolean isQuoteAt(@NotNull String s, int ind) {
    char ch = s.charAt(ind);
    return ch == '\'' || ch == '\"';
  }

  @Contract(pure = true)
  public static boolean isQuotedString(@NotNull String s) {
    return s.length() > 1 && isQuoteAt(s, 0) && s.charAt(0) == s.charAt(s.length() - 1);
  }

  @NotNull
  @Contract(pure = true)
  public static String unquoteString(@NotNull String s) {
    if (isQuotedString(s)) {
      return s.substring(1, s.length() - 1);
    }
    return s;
  }

  @NotNull
  @Contract(pure = true)
  public static String unquoteString(@NotNull String s, char quotationChar) {
    if (s.length() > 1 && quotationChar == s.charAt(0) && quotationChar == s.charAt(s.length() - 1)) {
      return s.substring(1, s.length() - 1);
    }
    return s;
  }
}