class Test {
    <A, B> B foo(A value, FunctionalI<A, B> fun) {
        return fun.apply(value);
    }

    Double toDouble<caret>(Integer x) {
        return x.doubleValue();
    }

    public double nya() {
        return foo(1, this::toDouble);
    }
}
