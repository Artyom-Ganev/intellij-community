// "Replace Stream API chain with loop" "true"

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

public class Main {
  public static void test(List<String> strings) {
    System.out.println(strings.stream().c<caret>ollect(Collectors.partitioningBy((String s) -> s.length() > 2, Collectors.groupingBy(s -> s.charAt(0), Collectors.toSet()))));
  }

  public static void main(String[] args) {
    test(Arrays.asList("a", "bbb", "cccc", "dddd"));
  }
}
