//
//   Copyright 2016  Cityzen Data
//
//   Licensed under the Apache License, Version 2.0 (the "License");
//   you may not use this file except in compliance with the License.
//   You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
//   Unless required by applicable law or agreed to in writing, software
//   distributed under the License is distributed on an "AS IS" BASIS,
//   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
//   See the License for the specific language governing permissions and
//   limitations under the License.
//

package io.warp10.script.functions;

import io.warp10.WarpConfig;
import io.warp10.continuum.Configuration;
import io.warp10.continuum.store.Constants;
import io.warp10.script.NamedWarpScriptFunction;
import io.warp10.script.WarpScriptException;
import io.warp10.script.WarpScriptLib;
import io.warp10.script.WarpScriptStack;
import io.warp10.script.WarpScriptStackFunction;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Properties;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import com.google.common.base.Charsets;

/**
 * Execute WarpScript on a remote endpoint
 */
public class REXEC extends NamedWarpScriptFunction implements WarpScriptStackFunction {
  
  private final boolean enabled;
  
  private final boolean compress;

  public REXEC(String name) {
    this(name, false);
  }
  
  public REXEC(String name, boolean compress) {
    super(name);
  
    this.compress = compress;
    
    Properties props = WarpConfig.getProperties();
  
    this.enabled = null != props && "true".equals(props.getProperty(Configuration.WARPSCRIPT_REXEC_ENABLE));
  }
  
  @Override
  public Object apply(WarpScriptStack stack) throws WarpScriptException {
    
    if (!this.enabled) {
      throw new WarpScriptException(getName() + " is not enabled, set '" + Configuration.WARPSCRIPT_REXEC_ENABLE + "' to true to enable it.");
    }
    
    String endpoint = stack.pop().toString();
    
    String warpscript = stack.pop().toString();
    
    HttpURLConnection conn = null;
    
    try {
      URL url = new URL(endpoint);

      if (!"http".equals(url.getProtocol()) && !"https".equals(url.getProtocol())) {
        throw new WarpScriptException(getName() + " invalid endpoint protocol.");
      }
      
      conn = (HttpURLConnection) url.openConnection();
      conn.setChunkedStreamingMode(8192);
      conn.setRequestProperty("Accept-Encoding", "gzip");
      
      if (this.compress) {
        conn.setRequestProperty("Content-Type", "application/gzip");
      }
      
      conn.setDoInput(true);
      conn.setDoOutput(true);
      conn.setRequestMethod("POST");
      
      OutputStream connout = conn.getOutputStream();
      OutputStream out = connout;
      
      if (this.compress) {
        out = new GZIPOutputStream(out);
      }
      
      out.write(warpscript.getBytes(Charsets.UTF_8));
      out.write('\n');
      out.write(WarpScriptLib.SNAPSHOT.getBytes(Charsets.UTF_8));      
      out.write('\n');
      out.write(WarpScriptLib.TOOPB64.getBytes(Charsets.UTF_8));
      out.write('\n');
      
      if (this.compress) {
        out.close();
      }
      
      connout.flush();
      
      InputStream in = conn.getInputStream();
      
      if ("gzip".equals(conn.getContentEncoding())) {
        in = new GZIPInputStream(in);
      }
      
      if (HttpURLConnection.HTTP_OK != conn.getResponseCode()) {
        throw new WarpScriptException(getName() + " remote execution encountered an error: " + conn.getHeaderField(Constants.getHeader(Constants.HTTP_HEADER_ERROR_MESSAGE_DEFAULT)));
      }
      
      ByteArrayOutputStream baos = new ByteArrayOutputStream();
      
      byte[] buf = new byte[1024];
      
      while(true) {
        int len = in.read(buf);
        if (len < 0) {
          break;
        }
        baos.write(buf, 0, len);
      }

      byte[] bytes = baos.toByteArray();
      
      // Strip '[ ' ' ]'
      String result = new String(bytes, 2, bytes.length - 4, Charsets.US_ASCII);
      
      stack.push(result);
      
      stack.exec(WarpScriptLib.OPB64TO);
      stack.push("UTF-8");
      stack.exec(WarpScriptLib.BYTESTO);
      stack.exec(WarpScriptLib.EVAL);
      
    } catch (WarpScriptException e) {
      throw e;
    } catch (Exception e) {
      throw new WarpScriptException(e);
    } finally {
      if (null != conn) {
        conn.disconnect();
      }
    }
    
    return stack;
  }
}
