/*******************************************************************************
 * TeleStax, Open Source Cloud Communications
 * Copyright 2011-2016, Telestax Inc, Eolos IT Corp and individual contributors
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
 *
 */

package org.restcomm.sbc.call;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import javax.swing.event.EventListenerList;

import org.apache.log4j.Logger;
import org.infinispan.Cache;
import org.restcomm.sbc.call.Call;
import org.restcomm.sbc.call.CallStateChanged.State;
import org.restcomm.sbc.call.Call.Direction;
import org.restcomm.sbc.managers.CacheManager;
import org.restcomm.sbc.managers.MonitoringService;
import org.restcomm.sbc.media.MediaSession;
import org.restcomm.sbc.bo.CallDetailRecord;
import org.restcomm.sbc.bo.CallDetailRecordFilter;



/**
 * @author  ocarriles@eolos.la (Oscar Andres Carriles)
 * @date    24 nov. 2016 4:41:54
 * @class   CallManager.java
 *
 */
public class CallManager  {
	
	private Cache<String, Call> calls;
	//private static CallManager callManager;
	private static final Logger LOG = Logger.getLogger(CallManager.class);
	private MonitoringService monitoringService;
	private EventListenerList listenerList = new EventListenerList();
	
	public CallManager() {
		calls = CacheManager.getCacheManager().getCache("callmanager");
		calls.start();
		monitoringService = new MonitoringService(this);
	}
	/*
	public static CallManager getCallManager() {
		if(callManager==null) {
			callManager=new CallManager();
		}
		
		return callManager;
	}
	*/
	public void addCallManagerListener(CallManagerListener listener) {
	     listenerList.add(CallManagerListener.class, listener);
	}
	
	public Call createCall(final Call parent, final String sessionId,
            final String to, final String from,    
            final String direction, 
            final String forwardedFrom, final String callerName) {
		
	        
	    Call call=new Call(parent, sessionId, to, from, direction, callerName);
	    
	    this.fireCallCreatedEvent(call);
	    
	    calls.put(sessionId, call);
		
		if(LOG.isDebugEnabled()) {
			LOG.debug("calls.put "+sessionId+" hashcode "+call.hashCode());
		}
		
		return call;
		
	}
	
	
	public MediaSession getMediaSession(final String sessionId) {
		return calls.get(sessionId).getMediaSession();
	}
	
	public void changeCallStatus(String sessionId, int statusCode, String reasonPhrase) {
		if(LOG.isInfoEnabled()) {
			LOG.info("CallState changed "+statusCode+", "+reasonPhrase);
		}
		switch(statusCode) {
		case 404:
			changeCallStatus(sessionId, State.NOT_FOUND);
			return;
		case 408:
			changeCallStatus(sessionId, State.NO_ANSWER);
			return;
		case 480:
		case 486:
			changeCallStatus(sessionId, State.BUSY);
			return;
		case 487:
			changeCallStatus(sessionId, State.CANCELED);
			return;
		}			
		if(statusCode>=300 ) {
			changeCallStatus(sessionId, State.FAILED);
			return;
		}
		else if(statusCode>200)
			changeCallStatus(sessionId, State.RINGING);
		else if(statusCode>=200)
			changeCallStatus(sessionId, State.COMPLETED);
	
	}
	
	public void changeCallStatus(String sessionId, State status) {
		Call call=getCall(sessionId);
		call.setStatus(status);
		this.fireCallStateChangedEvent(call, status);
		
		switch(status) {
			case QUEUED:
				this.fireCallCreatedEvent(call);
				this.fireCallInitiatingEvent(call);
				break;
			case IN_PROGRESS:
				this.fireCallAnsweredEvent(call);
				this.fireCallBridgedEvent(call);
				break;
			case BUSY:
				this.fireCallBusyEvent(call);
				break;
			case NOT_FOUND:
				this.fireCallNotFoundEvent(call);
				break;
			case CANCELED:
				this.fireCallCanceledEvent(call);
				break;
			case NO_ANSWER:
				this.fireCallNotAnsweredEvent(call);
				break;
			case COMPLETED:				
				this.fireCallCompletedEvent(call);
				this.fireCallReleasedEvent(call);
				call.getMediaSession().finalize();	
				break;
			case FAILED:
				this.fireCallRejectedEvent(call);
				this.fireCallFailedEvent(call);	
				call.getMediaSession().finalize();	
				break;
			case RINGING:	
				if(call.getDirection()==Direction.INBOUND)
					this.fireCallIncomingEvent(call);
				else
					this.fireCallDialingEvent(call);
				
				this.fireCallAlertingEvent(call);
				break;
			
		}
	}
	
	
	public void remove(String sessionId)  {
		calls.remove(sessionId);
	}
	
	
	public Call getCall(String sessionId)  {
		Call call=calls.get(sessionId);
		if(LOG.isTraceEnabled()) {
			if(call!=null)
				LOG.trace("call hashCode "+call.hashCode());
		}
		
		return call;
	}
	
	
	@SuppressWarnings({ "unchecked", "rawtypes" })
	public Collection<Call> getCalls() {	
		ArrayList al=new ArrayList(calls.values());
		//Collections.sort(al);
		return al;
	}
	
	
	public int getTotalCalls(CallDetailRecordFilter filterForTotal) {
		int counter = 0;
		
		for(Object l:calls.values()) {
			CallDetailRecord call=(CallDetailRecord)l;
			String recipient=filterForTotal.getRecipient();
			String sender=filterForTotal.getSender();
			
			
			if(recipient!=null) {
				if(!call.getAnsweredBy().startsWith(recipient.replace("%", "")))
					continue;	
			}
			if(sender!=null) {
				if(!call.getFrom().startsWith(sender.replace("%", ""))) 
					continue;	
			}
			
			counter++;	
		}
		return counter;
	}

	public List<Call> getCalls(CallDetailRecordFilter filter) {	
		int offsetCounter=0;
		int limitCounter=0;
		int limit=filter.getLimit();
		int offset=filter.getOffset();
		
		ArrayList<Call> acalls=new ArrayList<Call>();
		
		for(Object l:calls.values()) {
			Call call=(Call)l;
			String recipient=filter.getRecipient();
			String sender=filter.getSender();
			
			
			if(recipient!=null) {
				if(!call.getCdr().getAnsweredBy().startsWith(recipient.replace("%", "")))
					continue;	
			}
			if(sender!=null) {
				if(!call.getCdr().getFrom().startsWith(sender.replace("%", ""))) 
					continue;	
			}
			offsetCounter++;
			
			if(offsetCounter>offset && limitCounter<limit) {
				acalls.add(call);
				limitCounter++;
			}
				
		}
		return acalls;
				
	}
	
	/*
	 */
	protected void fireCallCreatedEvent(Call call) {
	     // Guaranteed to return a non-null array
	     Object[] listeners = listenerList.getListenerList();
	     // Process the listeners last to first, notifying
	     // those that are interested in this event
	     for (int i = listeners.length-2; i>=0; i-=2) {
	         if (listeners[i]==CallManagerListener.class) {             
	             ((CallManagerListener)listeners[i+1]).onCallCreated(call);
	         }
	         
	     }
	 }
	
	/*
	 */
	protected void fireCallAnsweredEvent(Call call) {
	     // Guaranteed to return a non-null array
	     Object[] listeners = listenerList.getListenerList();
	     // Process the listeners last to first, notifying
	     // those that are interested in this event
	     for (int i = listeners.length-2; i>=0; i-=2) {
	         if (listeners[i]==CallManagerListener.class) {             
	             ((CallManagerListener)listeners[i+1]).onCallAnswered(call);
	         }
	         
	     }
	 }
	
	/*
	 */
	protected void fireCallNotAnsweredEvent(Call call) {
	     // Guaranteed to return a non-null array
	     Object[] listeners = listenerList.getListenerList();
	     // Process the listeners last to first, notifying
	     // those that are interested in this event
	     for (int i = listeners.length-2; i>=0; i-=2) {
	         if (listeners[i]==CallManagerListener.class) {             
	             ((CallManagerListener)listeners[i+1]).onCallNotAnswered(call);
	         }
	         
	     }
	 }
	
	/*
	 */
	protected void fireCallBusyEvent(Call call) {
	     // Guaranteed to return a non-null array
	     Object[] listeners = listenerList.getListenerList();
	     // Process the listeners last to first, notifying
	     // those that are interested in this event
	     for (int i = listeners.length-2; i>=0; i-=2) {
	         if (listeners[i]==CallManagerListener.class) {             
	             ((CallManagerListener)listeners[i+1]).onCallBusy(call);
	         }
	         
	     }
	 }

	/*
	 */
	protected void fireCallCanceledEvent(Call call) {
	     // Guaranteed to return a non-null array
	     Object[] listeners = listenerList.getListenerList();
	     // Process the listeners last to first, notifying
	     // those that are interested in this event
	     for (int i = listeners.length-2; i>=0; i-=2) {
	         if (listeners[i]==CallManagerListener.class) {             
	             ((CallManagerListener)listeners[i+1]).onCallCanceled(call);
	         }
	         
	     }
	 }
	
	/*
	 */
	protected void fireCallNotFoundEvent(Call call) {
	     // Guaranteed to return a non-null array
	     Object[] listeners = listenerList.getListenerList();
	     // Process the listeners last to first, notifying
	     // those that are interested in this event
	     for (int i = listeners.length-2; i>=0; i-=2) {
	         if (listeners[i]==CallManagerListener.class) {             
	             ((CallManagerListener)listeners[i+1]).onCallNotFound(call);
	         }
	         
	     }
	 }
	/*
	 */
	protected void fireCallReleasedEvent(Call call) {
	     // Guaranteed to return a non-null array
	     Object[] listeners = listenerList.getListenerList();
	     // Process the listeners last to first, notifying
	     // those that are interested in this event
	     for (int i = listeners.length-2; i>=0; i-=2) {
	         if (listeners[i]==CallManagerListener.class) {             
	             ((CallManagerListener)listeners[i+1]).onCallReleased(call);
	         }
	         
	     }
	 }

	/*
	 */
	protected void fireCallRejectedEvent(Call call) {
	     // Guaranteed to return a non-null array
	     Object[] listeners = listenerList.getListenerList();
	     // Process the listeners last to first, notifying
	     // those that are interested in this event
	     for (int i = listeners.length-2; i>=0; i-=2) {
	         if (listeners[i]==CallManagerListener.class) {             
	             ((CallManagerListener)listeners[i+1]).onCallRejected(call);
	         }
	         
	     }
	 }

	/*
	 */
	protected void fireCallIncomingEvent(Call call) {
	     // Guaranteed to return a non-null array
	     Object[] listeners = listenerList.getListenerList();
	     // Process the listeners last to first, notifying
	     // those that are interested in this event
	     for (int i = listeners.length-2; i>=0; i-=2) {
	         if (listeners[i]==CallManagerListener.class) {             
	             ((CallManagerListener)listeners[i+1]).onCallIncoming(call);
	         }
	         
	     }
	 }
	
	/*
	 */
	protected void fireCallDialingEvent(Call call) {
	     // Guaranteed to return a non-null array
	     Object[] listeners = listenerList.getListenerList();
	     // Process the listeners last to first, notifying
	     // those that are interested in this event
	     for (int i = listeners.length-2; i>=0; i-=2) {
	         if (listeners[i]==CallManagerListener.class) {             
	             ((CallManagerListener)listeners[i+1]).onCallDialing(call);
	         }
	         
	     }
	 }
	
	/*
	 */
	protected void fireCallInitiatingEvent(Call call) {
	     // Guaranteed to return a non-null array
	     Object[] listeners = call.getListeners().getListenerList();
	     // Process the listeners last to first, notifying
	     // those that are interested in this event
	     for (int i = listeners.length-2; i>=0; i-=2) {
	         if (listeners[i]==CallListener.class) {             
	             ((CallListener)listeners[i+1]).onCallInitiating();
	         }
	         
	     }
	 }
	
	/*
	 */
	protected void fireCallStateChangedEvent(Call call, State state) {
	     // Guaranteed to return a non-null array
	     Object[] listeners = call.getListeners().getListenerList();
	     // Process the listeners last to first, notifying
	     // those that are interested in this event
	     for (int i = listeners.length-2; i>=0; i-=2) {
	         if (listeners[i]==CallListener.class) {             
	             ((CallListener)listeners[i+1]).onCallStateChanged(state);
	         }
	         
	         
	     }
	 }
	
	/*
	 */
	protected void fireCallFailedEvent(Call call) {
	     // Guaranteed to return a non-null array
	     Object[] listeners = call.getListeners().getListenerList();
	     // Process the listeners last to first, notifying
	     // those that are interested in this event
	     for (int i = listeners.length-2; i>=0; i-=2) {
	         if (listeners[i]==CallListener.class) {             
	             ((CallListener)listeners[i+1]).onCallFailed();
	         }
	         
	     }
	 }
	
	
	/*
	 */
	protected void fireCallCompletedEvent(Call call) {
	     // Guaranteed to return a non-null array
	     Object[] listeners = call.getListeners().getListenerList();
	     // Process the listeners last to first, notifying
	     // those that are interested in this event
	     for (int i = listeners.length-2; i>=0; i-=2) {
	         if (listeners[i]==CallListener.class) {             
	             ((CallListener)listeners[i+1]).onCallCompleted();
	         }
	         
	     }
	 }
	
	/*
	 */
	protected void fireCallBridgedEvent(Call call) {
	     // Guaranteed to return a non-null array
	     Object[] listeners = call.getListeners().getListenerList();
	     // Process the listeners last to first, notifying
	     // those that are interested in this event
	     for (int i = listeners.length-2; i>=0; i-=2) {
	         if (listeners[i]==CallListener.class) {             
	             ((CallListener)listeners[i+1]).onCallBridged();
	         }
	         
	     }
	 }
	
	/*
	 */
	protected void fireCallRingingEvent(Call call) {
	     // Guaranteed to return a non-null array
	     Object[] listeners = call.getListeners().getListenerList();
	     // Process the listeners last to first, notifying
	     // those that are interested in this event
	     for (int i = listeners.length-2; i>=0; i-=2) {
	         if (listeners[i]==CallListener.class) {             
	             ((CallListener)listeners[i+1]).onCallRinging();
	         }
	         
	     }
	 }
	
	/*
	 */
	protected void fireCallAlertingEvent(Call call) {
	     // Guaranteed to return a non-null array
	     Object[] listeners = call.getListeners().getListenerList();
	     // Process the listeners last to first, notifying
	     // those that are interested in this event
	     for (int i = listeners.length-2; i>=0; i-=2) {
	         if (listeners[i]==CallListener.class) {             
	             ((CallListener)listeners[i+1]).onCallAlerting();
	         }
	         
	     }
	 }
	public MonitoringService getMonitoringService() {
		return monitoringService;
	}
	
}
