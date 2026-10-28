// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.pdf;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/** Offline XHTML field values. The whitelist prevents scripts, image loads and active CSS. */
final class PdfRichText {
    private PdfRichText() { }
    static String validate(String value,PdfLimits limits)throws IOException{
        var root=PdfSafeXml.parse(value.getBytes(StandardCharsets.UTF_8),limits).getDocumentElement();
        if(!"body".equals(root.getLocalName())||!"http://www.w3.org/1999/xhtml".equals(root.getNamespaceURI())){throw PdfObjects.invalid();}
        visit(root);return PdfSafeXml.serialize(root);
    }
    private static void visit(Element element)throws IOException{
        if(!"http://www.w3.org/1999/xhtml".equals(element.getNamespaceURI())||!Set.of("body","p","span","b","i","u","br").contains(element.getLocalName())){throw PdfObjects.unsupported();}
        var attributes=element.getAttributes();for(int i=0;i<attributes.getLength();i++){var attribute=attributes.item(i);if("http://www.w3.org/2000/xmlns/".equals(attribute.getNamespaceURI())){continue;}if(!attribute.getNodeName().equals("style")){throw PdfObjects.unsupported();}
            for(String declaration:attribute.getNodeValue().split(";")){if(declaration.isBlank()){continue;}int split=declaration.indexOf(':');if(split<=0){throw PdfObjects.invalid();}String key=declaration.substring(0,split).trim(),value=declaration.substring(split+1).trim();
                boolean valid=switch(key){case "color"->value.matches("#[0-9A-Fa-f]{6}");case "font-size"->value.matches("(?:[0-9]+(?:\\.[0-9]+)?)(?:pt)?");case "font-family"->value.matches("[A-Za-z0-9_ +'-]{1,160}");case "font-weight"->Set.of("normal","bold","400","700").contains(value);case "font-style"->Set.of("normal","italic").contains(value);case "text-decoration"->Set.of("none","underline").contains(value);default->false;};if(!valid){throw PdfObjects.unsupported();}
            }}
        for(Node node=element.getFirstChild();node!=null;node=node.getNextSibling()){if(node instanceof Element child){visit(child);}}
    }
}
