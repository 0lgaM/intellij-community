class A {
    private int i;
    private int j;
    private String s;

    public A(int i, int j) {
        this.i = i;
        this.j = j;
    }//ins and outs
//in: PsiParameter:i
//exit: SEQUENTIAL PsiExpressionStatement

    public NewMethodResult newMethod(int i) {
        this.i = i;
        return new NewMethodResult();
    }

    public class NewMethodResult {
        public NewMethodResult() {
        }
    }

    public A(int i, String s) {
        this.s = s;
        this.i = i;
    }
}