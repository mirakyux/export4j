// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.pdf;

import com.mirakyux.export4j.model.ExecutionContext;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import org.w3c.dom.Document;
import org.xml.sax.SAXException;

/** Offline XML with bounded events before constructing a DOM; no entity or resource resolver. */
final class PdfSafeXml {
    private PdfSafeXml() { }
    static Document parse(byte[] bytes,PdfLimits limits)throws IOException{
        if(bytes.length>limits.maxInputBytes()||bytes.length>limits.maxDecodedStreamBytes()){throw new PdfOperationException(PdfOperationException.Code.INPUT_LIMIT,0,-1);}
        try{
            var factory=XMLInputFactory.newFactory();factory.setProperty(XMLInputFactory.SUPPORT_DTD,false);factory.setProperty("javax.xml.stream.isSupportingExternalEntities",false);
            factory.setXMLResolver((a,b,c,d)->{throw new XMLStreamException("external XML resource rejected");});
            var reader=factory.createXMLStreamReader(new ByteArrayInputStream(bytes));int depth=0;long nodes=0,text=0;
            try{while(reader.hasNext()){ExecutionContext.checkpoint();int event=reader.next();if(event==XMLStreamConstants.DTD||event==XMLStreamConstants.ENTITY_REFERENCE){throw PdfObjects.unsupported();}
                if(event==XMLStreamConstants.START_ELEMENT){if(++depth>limits.maxDepth()){throw new PdfOperationException(PdfOperationException.Code.DEPTH_LIMIT,0,-1);}nodes+=1L+reader.getAttributeCount();}
                else if(event==XMLStreamConstants.END_ELEMENT){depth--;}
                else if(event==XMLStreamConstants.CHARACTERS||event==XMLStreamConstants.CDATA){text+=reader.getTextLength();}
                if(nodes>limits.maxObjects()){throw new PdfOperationException(PdfOperationException.Code.OBJECT_LIMIT,0,-1);}if(text>limits.maxTextCharacters()){throw new PdfOperationException(PdfOperationException.Code.TEXT_LIMIT,0,-1);}
            }}finally{reader.close();}
            var dom=DocumentBuilderFactory.newInstance();dom.setNamespaceAware(true);dom.setXIncludeAware(false);dom.setExpandEntityReferences(false);dom.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING,true);
            dom.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);dom.setFeature("http://xml.org/sax/features/external-general-entities",false);dom.setFeature("http://xml.org/sax/features/external-parameter-entities",false);
            dom.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD,"");dom.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA,"");var builder=dom.newDocumentBuilder();builder.setEntityResolver((a,b)->{throw new SAXException("external XML resource rejected");});
            builder.setErrorHandler(new org.xml.sax.helpers.DefaultHandler(){@Override public void error(org.xml.sax.SAXParseException failure)throws SAXException{throw failure;}@Override public void fatalError(org.xml.sax.SAXParseException failure)throws SAXException{throw failure;}});
            return builder.parse(new ByteArrayInputStream(bytes));
        }catch(XMLStreamException|javax.xml.parsers.ParserConfigurationException|SAXException failure){throw new PdfOperationException(PdfOperationException.Code.INVALID_PDF,0,-1,failure);}
    }
    static String escape(String value){return value.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&apos;");}
    static String serialize(org.w3c.dom.Node node)throws IOException{try{var factory=javax.xml.transform.TransformerFactory.newInstance();factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING,true);factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD,"");factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET,"");var transformer=factory.newTransformer();transformer.setOutputProperty(javax.xml.transform.OutputKeys.OMIT_XML_DECLARATION,"yes");var output=new java.io.StringWriter();transformer.transform(new javax.xml.transform.dom.DOMSource(node),new javax.xml.transform.stream.StreamResult(output));return output.toString();}catch(javax.xml.transform.TransformerException failure){throw new IOException("XML serialization failed",failure);}}
}
