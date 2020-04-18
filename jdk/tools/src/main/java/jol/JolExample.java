package jol;

import org.openjdk.jol.info.ClassLayout;

/**
 * Description:
 *
 * @author: renfei
 * Version: 1.0
 * Create Date Time: 2020-04-07 11:52.
 */


class A {}

class B {
    private long s;
}

class C {
    private int a;
    private long s;
}

public class JolExample {
    int[] aa = new int[0];

    public static void main(String[] args) {
        A a = new A();
        System.out.println(ClassLayout.parseInstance(a).toPrintable());
        B b = new B();
        System.out.println(ClassLayout.parseInstance(b).toPrintable());
        C c = new C();
        System.out.println(ClassLayout.parseInstance(c).toPrintable());
        int[] aa = new int[0];
        System.out.println(ClassLayout.parseInstance(aa).toPrintable());
    }
}
