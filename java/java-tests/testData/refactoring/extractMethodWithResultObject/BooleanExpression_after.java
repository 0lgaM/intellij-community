class Test {
    void method(int i) {
        boolean isDirty = i == 0 || otherTests();
    }//ins and outs
//in: PsiParameter:i
//exit: EXPRESSION PsiBinaryExpression:i == 0

    public NewMethodResult newMethod(int i) {
        return new NewMethodResult(i == 0);
    }

    public class NewMethodResult {
        private boolean expressionResult;

        public NewMethodResult(boolean expressionResult) {
            this.expressionResult = expressionResult;
        }
    }

    boolean otherTests() { return true; }
}