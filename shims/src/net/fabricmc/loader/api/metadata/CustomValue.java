/*
 * API declaration following Fabric Loader (https://github.com/FabricMC/fabric-loader, Apache License 2.0) so that mods
 * compiled against it link; implementation by the Jay Loader authors. Licensed under the Apache License, Version 2.0
 * (licenses/Apache-2.0.txt).
 */
package net.fabricmc.loader.api.metadata;
import java.util.Map;
public interface CustomValue {
    enum CvType { OBJECT, ARRAY, STRING, NUMBER, BOOLEAN, NULL }
    CvType getType();
    CvObject getAsObject();
    CvArray getAsArray();
    String getAsString();
    Number getAsNumber();
    boolean getAsBoolean();
    interface CvObject extends CustomValue, Iterable<Map.Entry<String, CustomValue>> { int size(); boolean containsKey(String key); CustomValue get(String key); }
    interface CvArray extends CustomValue, Iterable<CustomValue> { int size(); CustomValue get(int index); }
}
