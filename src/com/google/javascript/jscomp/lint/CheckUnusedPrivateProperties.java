/*
 * Copyright 2015 The Closure Compiler Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.javascript.jscomp.lint;

import static com.google.common.base.Preconditions.checkState;

import com.google.javascript.jscomp.AbstractCompiler;
import com.google.javascript.jscomp.CompilerPass;
import com.google.javascript.jscomp.DiagnosticType;
import com.google.javascript.jscomp.NodeTraversal;
import com.google.javascript.jscomp.NodeUtil;
import com.google.javascript.rhino.JSDocInfo;
import com.google.javascript.rhino.JSDocInfo.Visibility;
import com.google.javascript.rhino.Node;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * This pass looks for properties that are never read. These can be properties created using "this",
 * or static properties of constructors or interfaces. Explicitly ignored is the possibility that
 * these properties may be indirectly referenced using "for-in" or "Object.keys".
 */
public class CheckUnusedPrivateProperties implements CompilerPass, NodeTraversal.Callback {

  public static final DiagnosticType UNUSED_PRIVATE_PROPERTY =
      DiagnosticType.disabled("JSC_UNUSED_PRIVATE_PROPERTY", "Private property {0} is never read");

  private final AbstractCompiler compiler;
  private final Set<String> used = new LinkedHashSet<>();
  private final List<Node> candidates = new ArrayList<>();
  private final LinkedHashSet<String> constructorsAndInterfaces = new LinkedHashSet<>();

  public CheckUnusedPrivateProperties(AbstractCompiler compiler) {
    this.compiler = compiler;
  }

  @Override
  public void process(Node externs, Node root) {
    NodeTraversal.traverse(compiler, root, this);
  }

  private void reportUnused(NodeTraversal t) {
    for (Node n : candidates) {
      String propName = getPropName(n);
      if (!used.contains(propName)) {
        t.report(n, UNUSED_PRIVATE_PROPERTY, propName);
      }
    }
  }

  private String getPropName(Node n) {
    switch (n.getToken()) {
      case GETPROP:
      case MEMBER_FUNCTION_DEF:
        return n.getString();
      default:
        break;
    }
    throw new RuntimeException("Unexpected node type: " + n);
  }

  @Override
  public boolean shouldTraverse(NodeTraversal t, Node n, Node parent) {
    if (n.isScript()) {
      used.clear();
      // Even if a constructor is unused , we still want to prevent the construction of the class
      // outside the file.  i.e. a container of static methods.
      used.add("constructor");
      candidates.clear();
    }
    return true;
  }

  @Override
  public void visit(NodeTraversal t, Node n, Node parent) {
    switch (n.getToken()) {
      case SCRIPT:
        {
          // exiting the script, report any privates not used in the file.
          reportUnused(t);
          break;
        }

      case GETPROP:
        {
          String propName = n.getString();
          if (isPinningPropertyUse(n) || !isCandidatePropertyDefinition(n)) {
            used.add(propName);
          } else {
            // Only consider "private" properties.
            if (isCheckablePrivatePropDecl(n)) {
              candidates.add(n);
            }
          }
          break;
        }

      case MEMBER_FUNCTION_DEF:
        {
          // Only consider "private" methods.
          if (isCheckablePrivatePropDecl(n)) {
            candidates.add(n);
          }
          break;
        }

      case OBJECTLIT:
        {
          // Assume any object literal definition might be a reflection on the
          // class property.
          for (Node c = n.getFirstChild(); c != null; c = c.getNext()) {
            if (c.isStringKey() || c.isGetterDef() || c.isSetterDef() || c.isMemberFunctionDef()) {
              used.add(c.getString());
            }
          }
          break;
        }

      case CALL:
        // Look for properties referenced through a property rename function.
        Node target = n.getFirstChild();
        if (n.hasMoreThanOneChild()
            && compiler.getCodingConvention().isPropertyRenameFunction(target)) {
          Node propName = target.getNext();
          if (propName.isStringLit()) {
            used.add(propName.getString());
          }
        }
        break;

      case FUNCTION:
        JSDocInfo info = NodeUtil.getBestJSDocInfo(n);
        if (info != null && (info.isConstructor() || info.isInterface())) {
          recordConstructorOrInterface(n);
        }
        break;
      case CLASS:
        recordConstructorOrInterface(n);
        break;
      default:
        break;
    }
  }

  private void recordConstructorOrInterface(Node n) {
    String className = NodeUtil.getBestLValueName(NodeUtil.getBestLValue(n));
    if (className != null) {
      // If className is null, then this pass won't report any diagnostics on static members of this
      // class.
      constructorsAndInterfaces.add(className);
    }
  }

  private boolean isPrivatePropDecl(Node n) {
    // TODO(johnlenz): add support private by convention property definitions without JSDoc.
    // TODO(johnlenz): add support for checking protected properties in final classes
    // TODO(johnlenz): add support for checking "package" properties when checking an entire
    // library.
    JSDocInfo info = NodeUtil.getBestJSDocInfo(n);
    return (info != null && info.getVisibility() == Visibility.PRIVATE);
  }

  private boolean isCheckablePrivatePropDecl(Node n) {
    // TODO(tbreisacher): Look for uses of the typedef/interface in type expressions; warn if there
    // are no uses.
    JSDocInfo info = NodeUtil.getBestJSDocInfo(n);
    return isPrivatePropDecl(n) && !info.hasTypedefType() && !info.isInterface();
  }

  private boolean isCandidatePropertyDefinition(Node n) {
    checkState(n.isGetProp(), n);
    Node target = n.getFirstChild();
    return target.isThis()
        || constructorsAndInterfaces.contains(target.getQualifiedName())
        || (target.isGetProp() && target.getString().equals("prototype"));
  }

  /**
   * @return Whether the property is used in a way that prevents its removal.
   */
  private static boolean isPinningPropertyUse(Node n) {
    // Rather than looking for cases that are uses, we assume all references are
    // pinning uses unless they are:
    //  - a simple assignment (x.a = 1)
    //  - a compound assignment or increment (x++, x += 1) whose result is
    //    otherwise unused

    Node parent = n.getParent();
    if (n == parent.getFirstChild()) {
      if (parent.isExprResult()) {
        // A stub declaration "this.x;" isn't a pinning use.
        return false;
      } else if (parent.isAssign()) {
        // A simple assignment doesn't pin the property.
        return false;
      } else if (NodeUtil.isAssignmentOp(parent) || parent.isInc() || parent.isDec()) {
        // In general, compound assignments are both reads and writes, but
        // if the property is never otherwise read we can consider it simply
        // a write.
        // However if the assign expression is used as part of a larger
        // expression, we must consider it a read. For example:
        //    x = (y.a += 1);
        return NodeUtil.isExpressionResultUsed(parent);
      }
    }
    return true;
  }
}
