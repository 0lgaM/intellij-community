class UseBuilder {
    void test(Builder builder, int[] arr) {
        builder.foo("xyz").bar(arr[0]).foo("abc");
    }//ins and outs
//in: PsiParameter:arr
//in: PsiParameter:builder
//exit: SEQUENTIAL PsiMethod:test

    NewMethodResult newMethod(Builder builder, int[] arr) {
        builder.foo("xyz").bar(arr[0]).foo("abc");
        return new NewMethodResult();
    }

    class NewMethodResult {
        public NewMethodResult() {
        }
    }

    static class Builder {
        Builder foo(String s) {
            return this;
        }

        Builder bar(int x) {
            return this;
        }
    }
}
