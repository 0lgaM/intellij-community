class X {
  static String guessTestDataName(String method, String testName, String[] methods) {
    for (String psiMethod : methods) {
        NewMethodResult x = newMethod(method, testName);
        if (x.exitKey == 1) return x.returnResult;
        String strings = method;
      if (strings != null && !strings.isEmpty()) {
        return strings.substring(0) + testName;
      }

    }
    return null;
  }//ins and outs
//in: PsiParameter:method
//in: PsiParameter:testName
//exit: RETURN PsiMethod:guessTestDataName<-PsiBinaryExpression:strings.substring(0) + testName
//exit: SEQUENTIAL PsiForeachStatement

    static NewMethodResult newMethod(String method, String testName) {
        String strings = method;
        if (strings != null && !strings.isEmpty()) {
            return new NewMethodResult((1 /* exit key */), strings.substring(0) + testName);
        }
        return new NewMethodResult((-1 /* exit key */), (null /* missing value */));
    }

    static class NewMethodResult {
        private int exitKey;
        private String returnResult;

        public NewMethodResult(int exitKey, String returnResult) {
            this.exitKey = exitKey;
            this.returnResult = returnResult;
        }
    }
}
