import java.util.List;

class ArgumentFoldingWholeStatement {
    List<String> x;
    List<String> y;

    private void foo() {

        NewMethodResult x1 = newMethod();
        for (int i = 0; ++i < 5; x.indexOf(str())) {
            baz();
        }
    }//ins and outs
//exit: SEQUENTIAL PsiMethod:foo

    NewMethodResult newMethod() {
        for (int i = 0; ++i < 5; x.indexOf(str())) {
            baz();
        }
        return new NewMethodResult();
    }

    static class NewMethodResult {
        public NewMethodResult() {
        }
    }

    private void bar() {
        for (int i = 0; ++i < 5; y.indexOf(str())) {
            baz();
        }
    }

    private String str() { return null; }
    private void baz() { }
}
