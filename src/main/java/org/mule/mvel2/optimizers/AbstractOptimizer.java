/**
 * MVEL 2.0
 * Copyright (C) 2007 The Codehaus
 * Mike Brock, Dhanji Prasanna, John Graham, Mark Proctor
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.mule.mvel2.optimizers;

import org.mule.mvel2.CompileException;
import org.mule.mvel2.MVEL;
import org.mule.mvel2.compiler.AbstractParser;

import java.lang.reflect.Method;

import static java.lang.Thread.currentThread;
import static org.mule.mvel2.util.ParseTools.*;

/**
 * @author Christopher Brock
 */
public class AbstractOptimizer extends AbstractParser {
  protected static final int BEAN = 0;
  protected static final int METH = 1;
  protected static final int COL = 2;
  protected static final int WITH = 3;
  protected static final int ESCAPED_BEAN = 4;

  protected boolean collection = false;
  protected boolean nullSafe = MVEL.COMPILER_OPT_NULL_SAFE_DEFAULT;
  protected Class currType = null;
  protected boolean staticAccess = false;

  protected int tkStart;

  /**
   * Try static access of the property, and return an instance of the Field, Method of Class if successful.
   *
   * @return - Field, Method or Class instance.
   */
  protected Object tryStaticAccess() {
    int begin = cursor;
    try {
      /**
       * Try to resolve this *smartly* as a static class reference.
       *
       * This starts at the end of the token and starts to step backwards to figure out whether
       * or not this may be a static class reference.  We search for method calls simply by
       * inspecting for ()'s.  The first union area we come to where no brackets are present is our
       * test-point for a class reference.  If we find a class, we pass the reference to the
       * property accessor along  with trailing methods (if any).
       *
       */
      boolean meth = false;
      // int end = start + length;
      int last = end;
      for (int i = end - 1; i > start; i--) {
        switch (expr[i]) {
          case '.':
            if (!meth) {
              ClassLoader classLoader = pCtx != null ? pCtx.getClassLoader() : currentThread().getContextClassLoader();
              try {
                String test = new String(expr, start, (cursor = last) - start);
                if (MVEL.COMPILER_OPT_SUPPORT_JAVA_STYLE_CLASS_LITERALS && test.endsWith(".class"))
                    test = test.substring(0, test.length() - 6);

                return Class.forName(test, true, classLoader);
              }
              catch (ClassNotFoundException e) {
                Class cls = forNameWithInner(new String(expr, start, i - start), classLoader);
                String name = new String(expr, i + 1, end - i - 1);
                try {
                  return cls.getField(name);
                }
                catch (NoSuchFieldException nfe) {
                  for (Method m : cls.getMethods()) {
                    if (name.equals(m.getName())) return m;
                  }
                  return null;
                }
              }
            }

            meth = false;
            last = i;
            break;

          case '}':
            i--;
            for (int d = 1; i > start && d != 0; i--) {
              switch (expr[i]) {
                case '}':
                  d++;
                  break;
                case '{':
                  d--;
                  break;
                case '"':
                case '\'':
                  char s = expr[i];
                  while (i > start && (expr[i] != s && expr[i - 1] != '\\')) i--;
              }
            }
            break;

          case ')':
            i--;

            for (int d = 1; i > start && d != 0; i--) {
              switch (expr[i]) {
                case ')':
                  d++;
                  break;
                case '(':
                  d--;
                  break;
                case '"':
                case '\'':
                  char s = expr[i];
                  while (i > start && (expr[i] != s && expr[i - 1] != '\\')) i--;
              }
            }

            meth = true;
            last = i++;
            break;


          case '\'':
            while (--i > start) {
              if (expr[i] == '\'' && expr[i - 1] != '\\') {
                break;
              }
            }
            break;

          case '"':
            while (--i > start) {
              if (expr[i] == '"' && expr[i - 1] != '\\') {
                break;
              }
            }
            break;
        }
      }
    }
    catch (Exception cnfe) {
      cursor = begin;
    }

    return null;
  }

  private Class forNameWithInner(String className, ClassLoader classLoader) throws ClassNotFoundException {
    ClassNotFoundException cnfe = null;
    try {
      return Class.forName(className, true, classLoader);
    } catch (ClassNotFoundException e) {
      cnfe = e;
    }

    for (int lastDotPos = className.lastIndexOf('.'); lastDotPos > 0; lastDotPos = className.lastIndexOf('.')) {
      className = className.substring(0, lastDotPos) + "$" + className.substring(lastDotPos+1);
      try {
        return Class.forName(className, true, classLoader);
      } catch (ClassNotFoundException e) { }
    }

     throw cnfe;
  }

  protected int nextSubToken() {
    skipWhitespace();
    nullSafe = MVEL.COMPILER_OPT_NULL_SAFE_DEFAULT;
    if (nullSafe) fields = -1;
    
    switch (expr[tkStart = cursor]) {
      case '[':
        return COL;
      case '{':
        if (expr[cursor - 1] == '.') {
          return WITH;
        }
        break;
      case '.':
        cursor = ++tkStart;
        while (cursor < end && isWhitespace(expr[cursor])) cursor = ++tkStart;
        
        if (cursor == end) {
            throw new CompileException("unexpected end of statement", expr, start);
        }
        
        switch (expr[cursor]) {
          case '?':
            skipWhitespace();
            if ((cursor = ++tkStart) == end) {
              throw new CompileException("unexpected end of statement", expr, start);
            }
            nullSafe = true;

            fields = -1;
            break;
          case '\'':
              // we've got an escaped bean property, e.g. foo.'bar'.baz
              if ((cursor = ++tkStart) == end) {
                throw new CompileException("unexpected end of statement", expr, start);
              }
              
              // move the cursor to the end of the property name
              while (++cursor < end && (expr[cursor] != '\''
                      // handle escaped single quotes
                      || expr[cursor-1] == '\\')) ;

              // advance it to the end quote and check for end of statement
              if (++cursor > end) {
                  throw new CompileException("unexpected end of statement", expr, start);
              }

              fields = -1;
              return ESCAPED_BEAN;
          case '[':
              skipWhitespace();
              if ((cursor = ++tkStart) == end) {
                throw new CompileException("unexpected end of statement", expr, start);
              }
              nullSafe = true;

              fields = -1;
          case '{':
            return WITH;
          default:
            if (isWhitespace(expr[tkStart])) {
              skipWhitespace();
              tkStart = cursor;
            }
        }
          
        break;
      case '?':
        if (start == cursor) {
          tkStart++;
          cursor++;
          nullSafe = true;
          fields = -1;
        }
    }

    //noinspection StatementWithEmptyBody
    while (++cursor < end && isIdentifierPart(expr[cursor])) ;

    skipWhitespace();
    if (cursor < end) {
      switch (expr[cursor]) {
        case '[':
          return COL;
        case '(':
          return METH;
        case '\'':
            return ESCAPED_BEAN;
        default:
          return BEAN;
      }
    }

    return 0;
  }

  protected String capture() {
    /**
     * Trim off any whitespace.
     */
    return new String(expr, tkStart = trimRight(tkStart), trimLeft(cursor) - tkStart);
  }

  protected String captureEscaped() {
    /**
     * Parse escaped property.
     */
    int start = tkStart;
    int end = cursor;
    ++cursor;
    return new String(expr, start, end - start -1).replaceAll("\\\\'", "'");
  }

  /**
   * Skip to the next non-whitespace position.
   */
  protected void whiteSpaceSkip() {
    if (cursor < length)
      //noinspection StatementWithEmptyBody
      while (isWhitespace(expr[cursor]) && ++cursor != length) ;
  }

  /**
   * @param c - character to scan to.
   * @return - returns true is end of statement is hit, false if the scan scar is countered.
   */
  protected boolean scanTo(char c) {
    for (; cursor < end; cursor++) {
      switch (expr[cursor]) {
        case '\'':
        case '"':
          cursor = captureStringLiteral(expr[cursor], expr, cursor, end);
        default:
          if (expr[cursor] == c) {
            return false;
          }
      }
    }
    return true;
  }

  protected int findLastUnion() {
    int split = -1;
    int depth = 0;

    int end = start + length;
    for (int i = end - 1; i != start; i--) {
      switch (expr[i]) {
        case '}':
        case ']':
          depth++;
          break;

        case '{':
        case '[':
          if (--depth == 0) {
            split = i;
            collection = true;
          }
          break;
        case '.':
          if (depth == 0) {
            split = i;
          }
          break;
      }
      if (split != -1) break;
    }

    return split;
  }
}
