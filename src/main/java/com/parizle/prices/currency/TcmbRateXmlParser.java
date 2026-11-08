package com.parizle.prices.currency;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

@Component
public class TcmbRateXmlParser {

    private static final DateTimeFormatter TCMB_DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    public TcmbRates parse(byte[] xml, LocalDate expectedDate) {
        Document document = readDocument(xml);
        Element root = document.getDocumentElement();
        if (!"Tarih_Date".equals(root.getTagName())) {
            throw new TcmbRateException("Unexpected TCMB document root: " + root.getTagName());
        }

        LocalDate rateDate = parseDate(root.getAttribute("Tarih"));
        if (!rateDate.equals(expectedDate)) {
            throw new TcmbRateException("TCMB bulletin contains date " + rateDate + " but expected " + expectedDate);
        }

        NodeList nodes = document.getElementsByTagName("Currency");
        List<TcmbRate> rates = new ArrayList<>(nodes.getLength());
        for (int i = 0; i < nodes.getLength(); i++) {
            Element currency = (Element) nodes.item(i);
            String code = currency.getAttribute("Kod");
            if (code.isBlank()) {
                code = currency.getAttribute("CurrencyCode");
            }
            String forexBuying = childText(currency, "ForexBuying");
            if (code.isBlank() || forexBuying == null || forexBuying.isBlank()) {
                continue;
            }
            BigDecimal rate = decimal(code, forexBuying);
            if (rate.signum() > 0) {
                rates.add(new TcmbRate(code.trim().toUpperCase(Locale.ROOT), unit(currency), rate));
            }
        }
        if (rates.isEmpty()) {
            throw new TcmbRateException("TCMB bulletin contains no forex buying rates");
        }
        return new TcmbRates(rateDate, List.copyOf(rates));
    }

    private static LocalDate parseDate(String value) {
        try {
            return LocalDate.parse(value, TCMB_DATE);
        } catch (DateTimeParseException e) {
            throw new TcmbRateException("Invalid TCMB bulletin date '" + value + "'", e);
        }
    }

    private static Document readDocument(byte[] xml) {
        try {
            DocumentBuilder builder = secureFactory().newDocumentBuilder();
            return builder.parse(new ByteArrayInputStream(xml));
        } catch (ParserConfigurationException | SAXException | IOException e) {
            throw new TcmbRateException("Could not parse TCMB rate XML", e);
        }
    }

    private static DocumentBuilderFactory secureFactory() throws ParserConfigurationException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        return factory;
    }

    private static BigDecimal decimal(String code, String value) {
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException e) {
            throw new TcmbRateException("Invalid TCMB forex buying rate '" + value + "' for " + code, e);
        }
    }

    private static int unit(Element currency) {
        String value = childText(currency, "Unit");
        if (value == null || value.isBlank()) {
            return 1;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw new TcmbRateException("Invalid TCMB unit value '" + value + "'", e);
        }
    }

    private static String childText(Element parent, String tagName) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.ELEMENT_NODE && tagName.equals(child.getNodeName())) {
                return child.getTextContent().trim();
            }
        }
        return null;
    }
}
