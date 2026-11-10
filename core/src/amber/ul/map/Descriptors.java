package amber.ul.map;

import java.util.function.UnaryOperator;

/** Descriptor helpers: map every class name inside a field/method descriptor. */
public final class Descriptors {
    private Descriptors() {}

    public static String mapClasses(String desc, UnaryOperator<String> map) {
        if (desc == null || desc.indexOf('L') < 0) return desc;
        StringBuilder sb = new StringBuilder(desc.length() + 16);
        int i = 0, n = desc.length();
        while (i < n) {
            char ch = desc.charAt(i);
            if (ch == 'L') {
                int e = desc.indexOf(';', i);
                sb.append('L').append(map.apply(desc.substring(i + 1, e))).append(';');
                i = e + 1;
            } else { sb.append(ch); i++; }
        }
        return sb.toString();
    }
}
