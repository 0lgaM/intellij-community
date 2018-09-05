package com.siyeh.igtest.bugs.assert_with_side_effects;
import java.sql.*;
import java.util.*;
import java.util.regex.*;
public class AssertWithSideEffects {
    private int sideEffect = 0;
    private boolean noEffect = false;
    void foo(boolean b) {
        assert !b && hasNoSideEffects();
    }

    void bar(int i) {
        assert i++ < 10;
    }

    void abc() {
        assert isSideEffect();
    }

    boolean isSideEffect() {
        sideEffect = 1;
        return true;
    }

    boolean hasNoSideEffects() {
        assert !noEffect;
        return noEffect;
    }

    void jdbc(ResultSet rs) throws SQLException {
      assert rs.last();
    }

    void assertMutation(Set<String> set) {
        assert set.add("foo");

        Matcher m = Pattern.compile("foobar").matcher("foo");
        assert m.matches();
    }
}