class Test {

  public void method1()
  {
    System.out.println(((1)));
    ;;
    System.out.println(2);
  }

  public void method2()
  {
      NewMethodResult x = newMethod();
      System.out.println(1);
    System.out.println(2);
  }//ins and outs
//exit: SEQUENTIAL PsiMethod:method2

    NewMethodResult newMethod() {
        System.out.println(1);
        System.out.println(2);
        return new NewMethodResult();
    }

    static class NewMethodResult {
        public NewMethodResult() {
        }
    }
}