class C {
    void foo() {
        for(int i = 0; i < 10; i++){
            NewMethodResult x = newMethod(i);
            if (x.exitKey == 1) continue;
        }
    }//ins and outs
//in: PsiLocalVariable:i
//exit: CONTINUE PsiBlockStatement<-PsiContinueStatement
//exit: SEQUENTIAL PsiMethod:foo

    NewMethodResult newMethod(int i) {
        if (i < 10){
            return new NewMethodResult((1 /* exit key */));
        }
        return new NewMethodResult((-1 /* exit key */));
    }

    static class NewMethodResult {
        private int exitKey;

        public NewMethodResult(int exitKey) {
            this.exitKey = exitKey;
        }
    }

    {
        for(int i = 0; i < 10; i++){
          if (i < 10){ continue;}
        }
    }
}