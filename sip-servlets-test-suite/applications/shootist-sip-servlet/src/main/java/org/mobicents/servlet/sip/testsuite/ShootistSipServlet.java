/*
 * TeleStax, Open Source Cloud Communications
 * Copyright 2011-2014, Telestax Inc and individual contributors
 * by the @authors tag.
 *
 * This program is free software: you can redistribute it and/or modify
 * under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation; either version 3 of
 * the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>
 */

package org.mobicents.servlet.sip.testsuite;

import java.io.IOException;
import java.io.Serializable;
import java.io.UnsupportedEncodingException;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.annotation.Resource;
import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.sip.Address;
import javax.servlet.sip.Parameterable;
import javax.servlet.sip.ServletParseException;
import javax.servlet.sip.ServletTimer;
import javax.servlet.sip.SipApplicationSession;
import javax.servlet.sip.SipApplicationSessionEvent;
import javax.servlet.sip.SipApplicationSessionListener;
import javax.servlet.sip.SipFactory;
import javax.servlet.sip.SipServlet;
import javax.servlet.sip.SipServletContextEvent;
import javax.servlet.sip.SipServletListener;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipServletResponse;
import javax.servlet.sip.SipSession;
import javax.servlet.sip.SipSessionEvent;
import javax.servlet.sip.SipSessionListener;
import javax.servlet.sip.SipURI;
import javax.servlet.sip.TelURL;
import javax.servlet.sip.TimerListener;
import javax.servlet.sip.TimerService;
import javax.servlet.sip.URI;
import javax.sip.ListeningPoint;

import org.apache.log4j.Logger;
import org.mobicents.javax.servlet.sip.SipServletRequestExt;
import org.mobicents.javax.servlet.sip.SipSessionExt;
import org.mobicents.javax.servlet.sip.dns.DNSResolver;
import org.mobicents.servlet.sip.SipConnector;
import org.mobicents.servlet.sip.listener.SipConnectorListener;

public class ShootistSipServlet 
		extends SipServlet 
		implements SipServletListener,TimerListener, SipSessionListener, SipApplicationSessionListener, SipConnectorListener {
	private static final long serialVersionUID = 1L;
	private static final String CONTENT_TYPE = "text/plain;charset=UTF-8";
	private static final String ENCODE_URI = "encodedURI";
	private static final String TEST_ERROR_RESPONSE = "testErrorResponse";
	private static final String NO_BYE = "noBye";
	private static transient Logger logger = Logger.getLogger(ShootistSipServlet.class);
	
	public static AtomicBoolean isAlreadyAccessed = new AtomicBoolean(false);
	
	int numberOf183Responses = 0;
	int numberOf180Responses = 0;
	
	@Resource
	TimerService timerService;
	@Resource
	SipFactory sipFactory;
	
	ServletTimer keepAlivetimer;
	
	/** Creates a new instance of ShootistSipServlet */
	public ShootistSipServlet() {
	}

	@Override
	public void init(ServletConfig servletConfig) throws ServletException {
		logger.info("the shootist has been started");
		super.init(servletConfig);
	}		
	
	@Override
	protected void doProvisionalResponse(SipServletResponse resp)
			throws ServletException, IOException {	
		try {
			access();
			if(resp.getStatus() == 183) numberOf183Responses++;
			if(resp.getStatus() == 180) numberOf180Responses++;
			if(resp.getHeader("require") != null) {
				SipServletRequest prack = resp.createPrack();
				SipFactory sipFactory = (SipFactory) getServletContext().getAttribute(SIP_FACTORY);
				SipURI requestURI = sipFactory.createSipURI("LittleGuy", "" + System.getProperty("org.mobicents.testsuite.testhostaddr") + ":5080");
				prack.setRequestURI(requestURI);
				prack.send();
			}
			if(getServletContext().getInitParameter("cancelOn1xx") != null) {
				try {
					Thread.sleep(500);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
				try {
					resp.getRequest().getApplicationSession().setAttribute(TEST_ERROR_RESPONSE, "true");
					resp.getRequest().createCancel().send();
				} catch (IOException e) {
					logger.error(e);
				}
			}
			if(resp.getStatus() == 180 && getServletContext().getInitParameter("cancel") != null) {					
				String timeToWaitString = getServletContext().getInitParameter("servletTimer");
				if(timeToWaitString != null && !timeToWaitString.equals("0")) {
					SipServletRequest cancelRequest = resp.getRequest().createCancel();
					timerService.createTimer(resp.getRequest().getApplicationSession(), 500, false, (Serializable) cancelRequest);
				} 			
			}
		} finally {
			release();
		}
	}
	
	@Override
	protected void doSuccessResponse(SipServletResponse sipServletResponse)
			throws ServletException, IOException {
		try {
			access();
			logger.info("Got : " + sipServletResponse.getStatus() + " "
					+ sipServletResponse.getMethod());
			int status = sipServletResponse.getStatus();
			if (status == SipServletResponse.SC_OK && "REGISTER".equalsIgnoreCase(sipServletResponse.getMethod())) {
				sipServletResponse.getSession().setInvalidateWhenReady(false);
				timerService.createTimer(sipServletResponse.getApplicationSession(), 20000L, false, (Serializable)sipServletResponse.getSession());
			}
			if (status == SipServletResponse.SC_OK && "INVITE".equalsIgnoreCase(sipServletResponse.getMethod())) {
				SipServletRequest ackRequest = sipServletResponse.createAck();
				ackRequest.send();
				if(System.currentTimeMillis() - sipServletResponse.getSession().getLastAccessedTime() > 500) {
					logger.error("lastAccessedTime was not updated => lastAccessedTime " + sipServletResponse.getSession().getLastAccessedTime() + " current Time  " + System.currentTimeMillis());
					return;
				}
				
				if(sipServletResponse.getRequest().isInitial() && !(sipServletResponse.getFrom().getURI() instanceof TelURL) && !(sipServletResponse.getTo().getURI() instanceof TelURL) && 
						((SipURI)sipServletResponse.getFrom().getURI()).getUser() != null &&
						(((SipURI)sipServletResponse.getFrom().getURI()).getUser().equals("reinvite") || ((SipURI)sipServletResponse.getTo().getURI()).getUser().equals("reinvite"))) {
					SipServletRequest request=sipServletResponse.getSession().createRequest("INVITE");				
					request.send();
				}  else {
					if(sipServletResponse.getApplicationSession().getAttribute(ENCODE_URI) == null && getServletContext().getInitParameter(NO_BYE) == null) {
						String timeToWaitForBye = getServletContext().getInitParameter("timeToWaitForBye");
						int delay = 2000;
						if(timeToWaitForBye != null) {
							delay = Integer.parseInt(timeToWaitForBye);
						}
						SipServletRequest sipServletRequest = sipServletResponse.getSession().createRequest("BYE");
						ServletTimer timer = timerService.createTimer(sipServletResponse.getApplicationSession(), delay, false, (Serializable)sipServletRequest);
						sipServletResponse.getApplicationSession().setAttribute("timer", timer);
					}
				}
			}
			if(getServletContext().getInitParameter("closeReliableChannel")!= null) {
				timerService.createTimer(sipFactory.createApplicationSession(), Long.valueOf(getServletContext().getInitParameter("timeout")), false, "" + sipServletResponse.getInitialRemotePort());
			}
			if(getServletContext().getInitParameter("testKeepAlive") != null && sipServletResponse.getInitialRemotePort() != 5081) {
				SipConnector[] sipConnectors = (SipConnector[]) getServletContext().getAttribute("org.mobicents.servlet.sip.SIP_CONNECTORS");
				for (SipConnector sipConnector : sipConnectors) {
					if(sipConnector.getIpAddress().equals(sipServletResponse.getLocalAddr()) && sipConnector.getPort() == sipServletResponse.getLocalPort() && sipConnector.getTransport().equals(sipServletResponse.getTransport())) {
						sipServletResponse.getApplicationSession().setAttribute("keepAliveAddress", sipServletResponse.getInitialRemoteAddr());
						sipServletResponse.getApplicationSession().setAttribute("keepAlivePort", sipServletResponse.getInitialRemotePort());
						sipServletResponse.getApplicationSession().setAttribute("keepAlivetoSend", Integer.valueOf(getServletContext().getInitParameter("testKeepAlive")));
						keepAlivetimer = timerService.createTimer(sipServletResponse.getApplicationSession(), 0, 2000, false, false, sipConnector);
						return;
					}
				}
			}
		} finally {
			release();
		}
	}

	@Override
	protected void doRequest(SipServletRequest req) throws ServletException,
			IOException {
		ClassLoader cl = Thread.currentThread().getContextClassLoader();
		if(!cl.getClass().getSimpleName().equals("WebappClassLoader")) {
			logger.error("ClassLoader " + cl);
			throw new IllegalArgumentException("Bad Context Classloader : " + cl);
		}
		super.doRequest(req);
	}
	
	@Override
	protected void doResponse(SipServletResponse resp) throws ServletException,
			IOException {
		ClassLoader cl = Thread.currentThread().getContextClassLoader();
		if(!cl.getClass().getSimpleName().equals("WebappClassLoader")) {
			logger.error("ClassLoader " + cl);
			throw new IllegalArgumentException("Bad Context Classloader : " + cl);
		}
		super.doResponse(resp);
	}
	
	@Override
	protected void doInvite(SipServletRequest req) throws ServletException,
			IOException {
		try {
			access();
			String requestURIStringified = req.getRequestURI().toString();
			logger.info(requestURIStringified);
			if(req.getTo().getURI().toString().contains("nonrecordrouteeinvite"))  {
				req.createResponse(200).send();
				return;
			}
			if(req.getTo().getURI().toString().contains("recordrouteeinvite"))  {
				((SipSessionExt)req.getSession()).setCopyRecordRouteHeadersOnSubsequentResponses(true);
				req.createResponse(200).send();
				return;
			}
			if(!requestURIStringified.startsWith("sip:mss@sip-servlets.com;org.mobicents.servlet.sip.ApplicationSessionKey=") && !requestURIStringified.endsWith("%3Aorg.mobicents.servlet.sip.testsuite.ShootistApplication")) {
				req.createResponse(500, "SipURI.toString() does not escape charachters according to RFC2396.").send();
			}				
			if(((SipURI)req.getFrom().getURI()).getUser().equalsIgnoreCase(ENCODE_URI)) {
				if(req.getApplicationSession().getAttribute(ENCODE_URI) != null) {
					req.createResponse(200).send();
				} else {
					req.createResponse(500, "received a request using the encodeURI mechanism but not the same sip application session").send();
				}
			} else {
				req.createResponse(500, "received a request using the encodeURI mechanism but not the same sip application session").send();
			}			
		} finally {
			release();
		}
	}
	
	@Override
	protected void doErrorResponse(SipServletResponse sipServletResponse)
			throws ServletException, IOException {
		try {
			access();
			logger.info("Got : " + sipServletResponse.getStatus());
			logger.info("remote address : " + sipServletResponse.getRemoteAddr());
			if(sipServletResponse.getRemoteAddr() == null) {
				// non regression test for https://github.com/Mobicents/sip-servlets/issues/59
				throw new IllegalArgumentException("remote address shouldn't be null for 408 Timeout generated by the container");
			}
			if(	getServletContext().getInitParameter("cancel") != null) {
				long now = System.currentTimeMillis(); 
				long timeSent = (Long) sipServletResponse.getApplicationSession().getAttribute("timeSent");
				if(now - timeSent > 30000) {
					sendMessage(sipFactory.createApplicationSession(), sipFactory, "30 sec passed");
				}
			}
			if(sipServletResponse.getStatus() == 408) {			
				sendMessage(sipFactory.createApplicationSession(), sipFactory, "408 received");
				sipServletResponse.getApplicationSession().invalidate();
			}
			if(sipServletResponse.getStatus() == 422) {						
				try {
					Thread.sleep(10000);
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
				sipServletResponse.getSession().createRequest("INVITE").send();				
			}
		} finally {
			release();
		}
	}
	
	@Override
	protected void doBye(SipServletRequest req) throws ServletException,
			IOException {
		try {
			access();
			ServletTimer timer = (ServletTimer) req.getApplicationSession().getAttribute("timer");
			if(timer != null) {
				timer.cancel();
			}
			req.createResponse(SipServletResponse.SC_OK).send();
		} finally {
			release();
		}
	}
	
	// SipServletListener methods
	/*
	 * (non-Javadoc)
	 * @see javax.servlet.sip.SipServletListener#servletInitialized(javax.servlet.sip.SipServletContextEvent)
	 */
	public void servletInitialized(SipServletContextEvent ce) {
		try {
			access();
			ClassLoader cl = Thread.currentThread().getContextClassLoader();		
			if(!cl.getClass().getSimpleName().equals("WebappClassLoader")) {
				logger.error("ClassLoader " + cl);			
				throw new IllegalArgumentException("Bad Context Classloader : " + cl);
			}
			SipFactory sipFactory = (SipFactory)ce.getServletContext().getAttribute(SIP_FACTORY);
			SipApplicationSession sipApplicationSession = sipFactory.createApplicationSession();
			
			String testServletListener = ce.getServletContext().getInitParameter("testServletListener");
			if(testServletListener != null) {
				logger.error("servlet initialized " + this);
				sendMessage(sipApplicationSession, sipFactory, "testServletListener");
				return;
			}
			String testContentLength = ce.getServletContext().getInitParameter("testContentLength");
			if(testContentLength != null) {
				sendMessage(sipApplicationSession, sipFactory, null);
				return;
			}
			
			String userName = ce.getServletContext().getInitParameter("username");
			if(userName == null || userName.length() < 1) {
				userName = "BigGuy";
			}
			if(userName.equalsIgnoreCase("nullTest")) {
				userName = "";
			}
			URI fromURI = sipFactory.createSipURI(userName, "here.com");
			URI toURI = null;
			if(ce.getServletContext().getInitParameter("urlType") != null) {
				if(ce.getServletContext().getInitParameter("urlType").equalsIgnoreCase("tel")) {
					try {
						toURI = sipFactory.createURI("tel:+358-555-1234567");
					} catch (ServletParseException e) {
						logger.error("Impossible to create the tel URL", e);
					}
					if(ce.getServletContext().getInitParameter("flag") != null) {
					    toURI.setParameter(ce.getServletContext().getInitParameter("flag"), "");
					}
					if(ce.getServletContext().getInitParameter("enum") != null) {
						DNSResolver dnsResolver = (DNSResolver) getServletContext().getAttribute("org.mobicents.servlet.sip.DNS_RESOLVER");
						toURI = dnsResolver.getSipURI(toURI);
					}
				} else if(ce.getServletContext().getInitParameter("urlType").equalsIgnoreCase("telAsSip")) {
					try {
						toURI = sipFactory.createURI("sip:+34666777888@192.168.0.20:5080");
					} catch (ServletParseException e) {
						logger.error("Impossible to create the tel URL as SIP", e);
					}
					
					try {
						toURI = sipFactory.createAddress("<sip:+34666777888@192.168.0.20:5080>").getURI();
					} catch (ServletParseException e) {
						logger.error("Impossible to create the tel URL as SIP", e);
					}
				}
			} else {
				toURI = sipFactory.createSipURI("LittleGuy", "there.com");
			}
			String toTag = ce.getServletContext().getInitParameter("toTag");
			if(toTag != null) {
				toURI.setParameter("tag", toTag);
			}
			String toParam = ce.getServletContext().getInitParameter("toParam");
			if(toParam != null) {
				toURI.setParameter("toParam", toParam);
			}
			
			String method = ce.getServletContext().getInitParameter("method");
			if(method == null) {
				method = "INVITE";
			}		
			SipServletRequest sipServletRequest = null;
			if(ce.getServletContext().getInitParameter("useStringFactory") != null) {
				try {
					sipServletRequest =	sipFactory.createRequest(sipApplicationSession, method, "sip:LittleGuy@there.com", userName);
					if(!sipServletRequest.getTo().toString().contains(userName)) {
						logger.error("To Address and username should match!");
						return; 
					}
				} catch (ServletParseException e) {
					logger.error("Impossible to create the " + method + " request ", e);
					return;
				}
			} else {
				sipServletRequest =	sipFactory.createRequest(sipApplicationSession, method, sipFactory.createAddress(fromURI, "from display"), sipFactory.createAddress(toURI,"to display"));
			}
			
			String authHeader = ce.getServletContext().getInitParameter("auth-header");
			if(authHeader != null) {
				// test Issue 1547 : can't add a Proxy-Authorization using SipServletMessage.addHeader
				// please note that addAuthHeader is not used here
				String headerToAdd = ce.getServletContext().getInitParameter("headerToAdd");				
				sipServletRequest.addHeader(headerToAdd, authHeader);
				if(headerToAdd.equals("Proxy-Authorization")) {
					sipServletRequest.addHeader("Proxy-Authenticate", authHeader);
				}
			}
			
			String routeHeader = ce.getServletContext().getInitParameter("route");
			if(routeHeader != null) {
				try {
					sipServletRequest.pushRoute((SipURI) sipFactory.createURI(routeHeader));
				} catch (ServletParseException e) {
					logger.error("Couldn't create Route Header from " + routeHeader);
					return; 
				}
			}
			
			String outboundInterface = ce.getServletContext().getInitParameter("outboundInterface");
			if(outboundInterface != null) {
				List<SipURI> outboundInterfaces = (List<SipURI>)getServletContext().getAttribute(OUTBOUND_INTERFACES);
	
				if(outboundInterfaces == null) throw new NullPointerException("Outbound interfaces should not be null");
	
				for(SipURI uri:outboundInterfaces) {
					logger.info("checking following outboudinterface"  + uri +" against transport" + outboundInterface);
					if(uri.toString().contains(outboundInterface)) {
						logger.info("using following outboudinterface"  + uri);
						// pick the lo interface, since its universal on all machines
						((SipSessionExt)sipServletRequest.getSession()).setOutboundInterface(uri);					
						break;
					}
				}
			}
			if(!method.equalsIgnoreCase("REGISTER") && !method.equalsIgnoreCase("OPTIONS") && !method.equalsIgnoreCase("MESSAGE")) {
				Address addr = null;
				try {
					addr = sipServletRequest.getAddressHeader("Contact");
				} catch (ServletParseException e1) {
				}
				if(addr == null) return; // Fail the test, we need that header
				String prack = ce.getServletContext().getInitParameter("prack");
				if(prack != null) {
					sipServletRequest.addHeader("Require", "100rel");
				}
				addr.setParameter("headerparam1", "headervalue1");
				addr.setParameter("param5", "ffff");
				addr.getURI().setParameter("uriparam", "urivalue");
			}
			String dontSetRURI = ce.getServletContext().getInitParameter("dontSetRURI");
			if(dontSetRURI == null) {
				String host = "" + System.getProperty("org.mobicents.testsuite.testhostaddr") + ":5080";
				if(ce.getServletContext().getInitParameter("testIOException") != null) {
					host = ce.getServletContext().getInitParameter("testIOException");
				}
				if(ce.getServletContext().getInitParameter("host") != null) {
					host = ce.getServletContext().getInitParameter("host");
				}
				SipURI requestURI = sipFactory.createSipURI("LittleGuy", host);
				requestURI.setSecure(ce.getServletContext().getInitParameter("secureRURI")!=null);
				if(ce.getServletContext().getInitParameter("encodeRequestURI") != null) {
					sipApplicationSession.encodeURI(requestURI);
					sipApplicationSession.setAttribute(ENCODE_URI, "true");
				}
				if(ce.getServletContext().getInitParameter("transportRURI") != null) {
					requestURI.setTransportParam(ce.getServletContext().getInitParameter("transportRURI"));
					if(method.equalsIgnoreCase("REGISTER")) {
						sipServletRequest.addHeader("Contact", "sips:LittleGuy@" + System.getProperty("org.mobicents.testsuite.testhostaddr") + ":5080");
					}
				}
				// http://code.google.com/p/sipservlets/issues/detail?id=156 and http://code.google.com/p/sipservlets/issues/detail?id=172 
				if(ce.getServletContext().getInitParameter("setRandomContact") != null) {
					if(method.equalsIgnoreCase("REGISTER")) {
						sipServletRequest.addHeader("Contact", "sip:random@172.172.172.172:3289");
					}
					if(method.equalsIgnoreCase("OPTIONS") || method.equalsIgnoreCase("MESSAGE")) {
						try {
							sipServletRequest.addHeader("Contact", "sip:random@172.172.172.172:3289");
							logger.error("JSR 289, Section 4.1.3 The Contact Header Field: Contact header is a system header  except for the following messages:  4. 200/OPTIONS responses. Container should have thrown an exception");
							throw new IllegalStateException("JSR 289, Section 4.1.3 The Contact Header Field: Contact header is a system header  except for the following messages:  4. 200/OPTIONS responses. Container should have thrown an exception");							
						} catch(Exception e) {							
						}						
						try {
							Address contactAddress = sipServletRequest.getAddressHeader("Contact");
							((SipURI)contactAddress.getURI()).setUser("optionUser");
							((SipURI)contactAddress.getURI()).setHost("optionHost.com");
							logger.error("JSR 289, Section 4.1.3 The Contact Header Field: Contact header is a system header  except for the following messages:  4. 200/OPTIONS responses. Container should have thrown an exception");
							throw new IllegalStateException("JSR 289, Section 4.1.3 The Contact Header Field: Contact header is a system header  except for the following messages:  4. 200/OPTIONS responses. Container should have thrown an exception");							
						} catch(Exception e) {							
						}
						try {
							Parameterable contactHeader = sipServletRequest.getParameterableHeader("Contact");
							contactHeader.setParameter("optionParam", "optionValue");
						} catch (ServletParseException e) {
							logger.error("an error occured while getting the contact Header", e);
						}
						
					}
				}	
//				try {
					sipServletRequest.setRequestURI(requestURI);
//				} catch (ServletParseException e) {
//					// TODO Auto-generated catch block
//					e.printStackTrace();
//				}
				if(ce.getServletContext().getInitParameter("enum") != null) {
					sipServletRequest.setRequestURI(toURI);
				}
			}
			String testErrorResponse = ce.getServletContext().getInitParameter(TEST_ERROR_RESPONSE);
			if(testErrorResponse != null) {
				sipServletRequest.getApplicationSession().setAttribute(TEST_ERROR_RESPONSE, "true");
			}		
			if(sipServletRequest.getTo().getParameter("tag") != null) {
				logger.error("the ToTag should be empty, not sending the request");
				return;
			}
			
			String testRemoteAddrAndPort = ce.getServletContext().getInitParameter("testRemoteAddrAndPort");
			if(testRemoteAddrAndPort != null) {
				sipServletRequest.getRemoteAddr();
				sipServletRequest.getRemotePort();
				sipServletRequest.getRemoteHost();
				sipServletRequest.getRemoteUser();
				sipServletRequest.getInitialRemoteAddr();
				sipServletRequest.getInitialPoppedRoute();
				sipServletRequest.getInitialRemotePort();
				sipServletRequest.getInitialTransport();
			}
			String testMultipleReasonHeaders = ce.getServletContext().getInitParameter("testMultipleReasonHeaders");
            if(testMultipleReasonHeaders != null) {
                sipServletRequest.addHeader("Reason", "SIP ;cause=200 ;text=\"Call completed elsewhere\"");               
                sipServletRequest.addHeader("Reason", "Q.850 ;cause=16 ;text=\"Terminated\"");
            }
			// test for http://code.google.com/p/sipservlets/issues/detail?id=31
			Parameterable paramVia = null;
			try {
				String via = sipServletRequest.getHeader("Via");
				paramVia = sipServletRequest.getParameterableHeader("Via");
				
				logger.info("Via Header " + via);
				logger.info("Param Via Header " + paramVia);								
			} catch (ServletParseException e) {
				logger.error("couln't parse the Via Header ", e);
			}
			paramVia.setParameter("rport", "");
			boolean gotException = false;
			try {
				paramVia.setParameter("branch", "");
			} catch (IllegalStateException e) {
				gotException = true;
			}
			if(gotException) {
				logger.info("got the expected exception on Via Header branch setting");
			} else {
				throw new IllegalStateException("didn't get the expected exception on branch modification of the Via Header"); 
			}
			if(testRemoteAddrAndPort != null) {
				sipServletRequest.getRemoteAddr();
				sipServletRequest.getRemotePort();
				sipServletRequest.getRemoteHost();
				sipServletRequest.getRemoteUser();
				sipServletRequest.getInitialRemoteAddr();
				sipServletRequest.getInitialPoppedRoute();
				sipServletRequest.getInitialRemotePort();
				sipServletRequest.getInitialTransport();
			}
			if(ce.getServletContext().getInitParameter("testMultipartBytes") != null) {
			    // https://code.google.com/p/sipservlets/issues/detail?id=169
			    try {
                    sipServletRequest.setContent(new String("test Multipart"), "multipart/unknown");
                } catch (UnsupportedEncodingException e) {
                    throw new IllegalStateException("couldn't set the content", e);
                }
			}
			if(ce.getServletContext().getInitParameter("testAddressParam") != null) {
			    // https://code.google.com/p/sipservlets/issues/detail?id=245
                try {
                    sipServletRequest.getFrom().setDisplayName("Test name");
                    sipServletRequest.getFrom().setParameter("epid", "00112233");
                    sipServletRequest.getTo().setParameter("epid", "33221100");
                    
                    Address address = sipFactory.createAddress(sipFactory.createURI("sip:test@address.example.com"));                
    			    address.setParameter("epid", "bzz");
    			    sipServletRequest.addAddressHeader("TestAddress", address, true);
    
    			    logger.info("testAddressParam Test Address" + address);
    			    logger.info("request " + sipServletRequest);
                } catch (ServletParseException e) {
                    // TODO Auto-generated catch block
                    e.printStackTrace();
                }
			}
			if(ce.getServletContext().getInitParameter("testGruu") != null) {
			    logger.info("testGruu Test Address");
			    // https://github.com/Mobicents/sip-servlets/issues/51
                try {
                	// Support for https://tools.ietf.org/html/rfc5627
                	Address address = sipFactory.createAddress("<sip:callee@example.com;gr=urn:uuid:f81d4fae-7dec-11d0-a765-00a0c91e6bf6>");
                	sipServletRequest.addAddressHeader("Contact", address, true);
                	logger.info("request " + sipServletRequest);
                	sipServletRequest.removeHeader("Contact");
                	
                	// Support for https://tools.ietf.org/html/draft-ietf-sip-gruu-10#section-14.1 for Lync interop
                	address = sipFactory.createAddress("<sip:caller@example.com;gruu;opaque=hdg7777ad7aflzig8sf7>");
                	sipServletRequest.addAddressHeader("Contact", address, true);
                	logger.info("request " + sipServletRequest);
    
                } catch (ServletParseException e) {
                    // TODO Auto-generated catch block
                    e.printStackTrace();
                }
			}
			try {			
				sipServletRequest.send();
			} catch (IOException e) {
				if(ce.getServletContext().getInitParameter("testIOException") != null) {
					logger.info("expected exception thrown" + e);
					((SipURI)sipServletRequest.getRequestURI()).setHost("" + System.getProperty("org.mobicents.testsuite.testhostaddr") + "");
					((SipURI)sipServletRequest.getRequestURI()).setPort(5080);
					((SipURI)sipServletRequest.getRequestURI()).setTransportParam("udp");
					try {
						sipServletRequest.send();
						sendMessage(sipApplicationSession, sipFactory, "IOException thrown");
					} catch (IOException e1) {
						logger.error("Unexpected exception while sending the INVITE request",e1);
					}					
				} else {
					logger.error("Unexpected exception while sending the INVITE request",e);
				}
			}
			if(ce.getServletContext().getInitParameter("cancel") != null) {
				SipServletRequest cancelRequest = sipServletRequest.createCancel();
				sipServletRequest.getApplicationSession().setAttribute(TEST_ERROR_RESPONSE, "true");
				String timeToWaitString = ce.getServletContext().getInitParameter("servletTimer");
				if(timeToWaitString == null) {
					try {
						Thread.sleep(500);
						cancelRequest.send();
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
					} catch (IOException e) {
						logger.error(e);
					}
				} else if(timeToWaitString.equals("0")) {
					if(getServletContext().getInitParameter("servletTimer") != null) {
						timerService.createTimer(sipServletRequest.getApplicationSession(), 0, false, (Serializable) cancelRequest);
						try {
							Thread.sleep(2000);
						} catch (InterruptedException e) {
							// TODO Auto-generated catch block
							e.printStackTrace();
						}
					} 								
				}
			}	
		} finally {
			release();
		}
	}

	public void timeout(ServletTimer timer) {
		try {
			Serializable info = timer.getInfo();
			if (info instanceof String) {
				String port = (String)info;
				SipConnector[] sipConnectors = (SipConnector[]) getServletContext().getAttribute("org.mobicents.servlet.sip.SIP_CONNECTORS");
				for (SipConnector sipConnector : sipConnectors) {
					if(sipConnector.getIpAddress().equals(System.getProperty("org.mobicents.testsuite.testhostaddr")) && sipConnector.getTransport().equalsIgnoreCase("TCP")) {
						try {							
							boolean changed = sipConnector.closeReliableConnection(System.getProperty("org.mobicents.testsuite.testhostaddr"), Integer.valueOf(port));							
							logger.info("SipConnector reliable connection killed changed " + System.getProperty("org.mobicents.testsuite.testhostaddr") +":"+ Integer.valueOf(port)+ " changed " + changed);
							if(changed) {
								keepAlivetimer.cancel();
							}							
						} catch (Exception e) {
							e.printStackTrace();
						}					
					}
				}
				return;
			}
			access();
			ClassLoader cl = Thread.currentThread().getContextClassLoader();
			if(!cl.getClass().getSimpleName().equals("WebappClassLoader")) {
				logger.error("ClassLoader " + cl);
				throw new IllegalArgumentException("Bad Context Classloader : " + cl);
			}			
			if(info instanceof SipConnector) {
				String remoteAddress = (String) timer.getApplicationSession().getAttribute("keepAliveAddress");
				int remotePort = (Integer) timer.getApplicationSession().getAttribute("keepAlivePort");
				int keepAlivetoSend = (Integer) timer.getApplicationSession().getAttribute("keepAlivetoSend");
				Integer keepAliveSent = (Integer) timer.getApplicationSession().getAttribute("keepAliveSent");
				if(keepAliveSent == null) {
					keepAliveSent = 0;
				}
				logger.info("keepalive sent " + keepAliveSent + " ,keepAliveToSend " + keepAlivetoSend);
				if(keepAliveSent < keepAlivetoSend) {
					try {
						((SipConnector)info).sendHeartBeat(remoteAddress, remotePort);
					} catch (Exception e) {
						logger.error("problem sending heartbeat to " + remoteAddress+":"+ remotePort);
						timer.cancel();
					}
					keepAliveSent = keepAliveSent.intValue() +1;
					timer.getApplicationSession().setAttribute("keepAliveSent", new Integer(keepAliveSent));
					if(keepAliveSent >= keepAlivetoSend) {
						try {
							((SipConnector)info).setKeepAliveTimeout(remoteAddress, remotePort, -1);
						} catch (Exception e) {
							// TODO Auto-generated catch block
							e.printStackTrace();
						}	
	                }
				}
			} else if(info instanceof SipSession){
				SipSession sipSession = (SipSession) info;
				try {
					sipSession.createRequest("REGISTER").send();
				} catch (IOException e) {
					logger.error("Unexpected exception while sending the REGISTER request",e);
				} 
			} else {
				SipServletRequest sipServletRequest = (SipServletRequest) timer.getInfo();				
				sipServletRequest.getApplicationSession().setAttribute("timeSent", Long.valueOf(System.currentTimeMillis()));
				try {
					// http://code.google.com/p/sipservlets/issues/detail?id=31
					sipServletRequest.getParameterableHeader("Via").setParameter("rport", "");
					sipServletRequest.send();
				} catch (IOException e) {
					logger.error("Unexpected exception while sending the BYE request",e);
				} catch (ServletParseException e) {
					logger.error("Couldn't parse via header for BYE request",e);
				}
				timer.cancel();
			}			
		} finally {
			release();
		}
	}
	
	public void access() {
		if(!isAlreadyAccessed.compareAndSet(false, true)) {
			sendMessage(sipFactory.createApplicationSession(), sipFactory, "servlet is already being accessed !", null);
		}
	}
	
	public void release() {
		isAlreadyAccessed.set(false);
	}
	
	/**
	 * @param sipApplicationSession
	 * @param storedFactory
	 */
	private void sendMessage(SipApplicationSession sipApplicationSession,
			SipFactory storedFactory, String content) {
		try {
			SipServletRequest sipServletRequest = storedFactory.createRequest(
					sipApplicationSession, 
					"MESSAGE", 
					"sip:sender@sip-servlets.com", 
					"sip:receiver@sip-servlets.com");
			SipURI sipUri=storedFactory.createSipURI("receiver", "" + System.getProperty("org.mobicents.testsuite.testhostaddr") + ":5080");
			sipServletRequest.setRequestURI(sipUri);
			if(content != null) {
				sipServletRequest.setContentLength(content.length());
				sipServletRequest.setContent(content, CONTENT_TYPE);
			} else {
				sipServletRequest.setContentLength(0);
			}
			sipServletRequest.send();
		} catch (ServletParseException e) {
			logger.error("Exception occured while parsing the addresses",e);
		} catch (IOException e) {
			logger.error("Exception occured while sending the request",e);			
		}
	}

	public void sessionCreated(SipSessionEvent se) {
		// TODO Auto-generated method stub
		
	}

	public void sessionDestroyed(SipSessionEvent se) {
		// TODO Auto-generated method stub
		
	}

	public void sessionReadyToInvalidate(SipSessionEvent se) {
		if(se.getSession().getApplicationSession().getAttribute(TEST_ERROR_RESPONSE) != null) {
			sendMessage(sipFactory.createApplicationSession(), sipFactory, "sipSessionReadyToInvalidate", null);
		}
	}

	public void sessionCreated(SipApplicationSessionEvent ev) {
		// TODO Auto-generated method stub
		
	}

	public void sessionDestroyed(SipApplicationSessionEvent ev) {
		// TODO Auto-generated method stub
		
	}

	public void sessionExpired(SipApplicationSessionEvent ev) {
		// TODO Auto-generated method stub
		
	}

	public void sessionReadyToInvalidate(SipApplicationSessionEvent ev) {
		if(ev.getApplicationSession().getAttribute(TEST_ERROR_RESPONSE) != null) {
			sendMessage(sipFactory.createApplicationSession(), sipFactory, "sipAppSessionReadyToInvalidate", null);
		}
	}
	
	/**
	 * @param sipApplicationSession
	 * @param storedFactory
	 */
	private void sendMessage(SipApplicationSession sipApplicationSession,
			SipFactory storedFactory, String content, String transport) {
		try {
			SipServletRequest sipServletRequest = storedFactory.createRequest(
					sipApplicationSession, 
					"MESSAGE", 
					"sip:sender@sip-servlets.com", 
					"sip:receiver@sip-servlets.com");
			sipServletRequest.addHeader("Ext", "Test 1, 2 ,3");
			SipURI sipUri = storedFactory.createSipURI("receiver", "" + System.getProperty("org.mobicents.testsuite.testhostaddr") + ":5080");
			if(transport != null) {
				if(transport.equalsIgnoreCase(ListeningPoint.TCP)) {
					sipUri = storedFactory.createSipURI("receiver", "" + System.getProperty("org.mobicents.testsuite.testhostaddr") + ":5081");
				}
				sipUri.setTransportParam(transport);
			}
			sipServletRequest.setRequestURI(sipUri);
			sipServletRequest.setContentLength(content.length());
			sipServletRequest.setContent(content, CONTENT_TYPE);
			sipServletRequest.setHeader("EarlyMediaResponses", new Integer(numberOf183Responses).toString());
			sipServletRequest.setHeader("EarlyMedia180Responses", new Integer(numberOf180Responses).toString());
			
			sipServletRequest.send();
		} catch (ServletParseException e) {
			logger.error("Exception occured while parsing the addresses",e);
		} catch (IOException e) {
			logger.error("Exception occured while sending the request",e);			
		}
	}
	

	@Override
	public void sipConnectorAdded(SipConnector connector) {
		
	}

	@Override
	public void sipConnectorRemoved(SipConnector connector) {
		
	}

	@Override
	public void onKeepAliveTimeout(SipConnector connector, String peerAddress,
			int peerPort) {
		logger.error("SipConnector " + connector + " remotePeer " + peerAddress +":"+ peerPort);
		keepAlivetimer.cancel();
		sendMessage(sipFactory.createApplicationSession(), sipFactory, "shootist onKeepAliveTimeout", "tcp");		
	}
}