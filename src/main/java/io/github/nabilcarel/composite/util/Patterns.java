package io.github.nabilcarel.composite.util;

import java.util.regex.Pattern;
import lombok.experimental.UtilityClass;

@UtilityClass
public class Patterns {
  public static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\$\\{([^}]+)}");
  public static final Pattern INDEX_PATTERN = Pattern.compile("\\[(\\d+)\\]");
  public static final Pattern INNERMOST_PATTERN = Pattern.compile("\\$\\{([^${}]+)\\}");
  public static final Pattern DEPENDENCY_SPLIT_PATTERN = Pattern.compile("[.\\[]");
}
