/**
 * Copyright © 2024 Apple Inc. and the Pkl project authors. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.pkl.core.httpsettings;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import org.pkl.core.PNull;
import org.pkl.core.PObject;
import org.pkl.core.PklException;
import org.pkl.core.util.ErrorMessages;
import org.pkl.core.util.Nullable;

/** Java version of {@code pkl.EvaluatorSettings}. */
public record PklEvaluatorSettings(@Nullable Proxy proxy) {
  public static PklEvaluatorSettings DEFAULT = new PklEvaluatorSettings(null);

  /** Initializes a {@link PklEvaluatorSettings} from a raw object representation. */
  public static PklEvaluatorSettings parse(PObject object) {
    var proxy = Proxy.parse(object.getProperty("proxy"));
    return new PklEvaluatorSettings(proxy);
  }

  public record Proxy(URI address, List<String> noProxy) {
    public static @Nullable Proxy parse(Object proxy) {
      if (proxy instanceof PNull) {
        return null;
      }

      var obj = (PObject) proxy;
      var address = (String) obj.getProperty("address");
      var noProxy = new ArrayList<String>();
      for (var s: (List<?>) obj.getProperty("noProxy")) {
        noProxy.add((String) s);
      }
      URI addressUri;
      try {
        addressUri = new URI(address);
      } catch (URISyntaxException e) {
        throw new PklException(ErrorMessages.create("invalidUri", address));
      }

      return new Proxy(addressUri, noProxy);
    }
  }
}
