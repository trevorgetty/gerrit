package com.google.gerrit.server.events;

import com.google.gson.JsonArray;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonPrimitive;
import com.google.gson.internal.LinkedTreeMap;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * We now persist event data for failed events after they have been deserialized by checkAndSortEvents.
 * EventWrapper has a GerritEventData member which in turn has a Map<String, Object> properties member.
 * The default type for numbers in the Gson deserializer is double. As we now write out failed events
 * we need to tell Gson how to deserialize this member properly so that we do not end up with doubles
 * being written where they shouldn't be.
 * @author ronan.conway
 */
public class GerritEventDataPropertiesDeserializer implements JsonDeserializer<Map<String, Object>> {

  @Override  @SuppressWarnings("unchecked")
  public Map<String, Object> deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) throws JsonParseException {
    return (Map<String, Object>) read(json);
  }

  public Object read(JsonElement jsonElement) {

    if(jsonElement.isJsonArray()){
      List<Object> list = new ArrayList<>();
      JsonArray arr = jsonElement.getAsJsonArray();
      for (JsonElement anArr : arr) {
        list.add(read(anArr));
      }
      return list;
    }else if(jsonElement.isJsonObject()){
      Map<String, Object> map = new LinkedTreeMap<>();
      JsonObject obj = jsonElement.getAsJsonObject();
      Set<Map.Entry<String, JsonElement>> entitySet = obj.entrySet();

      // Iterate through each entry in the entry set and put entry in the map with a key
      // and a value of calling read() recursively with the entry value to ensure correct type.
      for(Map.Entry<String, JsonElement> entry: entitySet){
        map.put(entry.getKey(), read(entry.getValue()));
      }

      return map;
    }else if( jsonElement.isJsonPrimitive()){
      JsonPrimitive prim = jsonElement.getAsJsonPrimitive();
      if(prim.isBoolean()){
        return prim.getAsBoolean();
      }else if(prim.isString()){
        return prim.getAsString();
      }else if(prim.isNumber()){
        Number num = prim.getAsNumber();
        //If math.ceil is still a double then it really is a double, otherwise just give a long
        if(Math.ceil(num.doubleValue())  == num.longValue())
          return num.longValue();
        else{
          return num.doubleValue();
        }
      }
    }
    return null;
  }
}
