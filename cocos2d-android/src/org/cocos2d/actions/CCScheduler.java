package org.cocos2d.actions;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;

import org.cocos2d.config.ccConfig;

//
// CCScheduler
//
/** Scheduler is responsible of triggering the scheduled callbacks.
 You should not use NSTimer. Instead use this class.
 
 There are 2 different types of callbacks (selectors):

	- update selector: the 'update' selector will be called every frame. You can customize the priority.
	- custom selector: A custom selector will be called every frame, or with a custom interval of time
 
 The 'custom selectors' should be avoided when possible. It is faster, and consumes less memory to use the 'update selector'.

*/
public class CCScheduler {

    // A list double-linked list used for "updates with priority"
    private static class tListEntry {
        // struct	_listEntry *prev, *next;
        public Method impMethod;
        public Object	target;				// not retained (retained by hashUpdateEntry)
        public int		priority;
        public boolean	paused;
    };

    // Hash Element used for "selectors with interval"
    private static class tHashSelectorEntry {
        ArrayList<CCTimer>    timers;
        Object			target;		// hash key (retained)
        int	            timerIndex;
        CCTimer			currentTimer;
        boolean			currentTimerSalvaged;
        boolean			paused;
        // UT_hash_handle  hh;
    }

	//
	// "updates with priority" stuff
	//
	ArrayList<tListEntry>    updatesNeg;	// list of priority < 0
	ArrayList<tListEntry>    updates0;	// list priority == 0
	ArrayList<tListEntry>    updatesPos;	// list priority > 0
		
	// Used for "selectors with interval"
    HashMap<Object, tHashSelectorEntry>  hashForSelectors;
    HashMap<Object, tHashSelectorEntry>  hashForUpdates;
	tHashSelectorEntry	                currentTarget;
	boolean						        currentTargetSalvaged;
	
	// Optimization
	Method			    impMethod;
	String				updateSelector;

    /** Modifies the time of all scheduled callbacks.
      You can use this property to create a 'slow motion' or 'fast fordward' effect.
      Default is 1.0. To create a 'slow motion' effect, use values below 1.0.
      To create a 'fast fordward' effect, use values higher than 1.0.
      @since v0.8
      @warning It will affect EVERY scheduled selector / action.
    */
    private float timeScale_;

    public float getTimeScale() {
        return timeScale_;
    }

    public void setTimeScale(float ts) {
        timeScale_ = ts;
    }

    private static CCScheduler _sharedScheduler = null;

    /** returns a shared instance of the Scheduler */
    public static CCScheduler sharedScheduler() {
        if (_sharedScheduler != null) {
            return _sharedScheduler;
        }
        synchronized (CCScheduler.class) {
            if (_sharedScheduler == null) {
                _sharedScheduler = new CCScheduler();
            }
            return _sharedScheduler;
        }
    }

    /** purges the shared scheduler. It releases the retained instance.
      @since v0.99.0
      */
    public static void purgeSharedScheduler() {
        _sharedScheduler = null;
    }

    private CCScheduler() {
        timeScale_ = 1.0f;

        // used to trigger CCTimer#update
        updateSelector = "update";
        try {
			impMethod = CCTimer.class.getMethod(updateSelector, new Class[]{Float.TYPE});
		} catch (Exception e) {
			impMethod = null;
		}

        // updates with priority
        updates0 = new ArrayList<tListEntry>();
        updatesNeg = new ArrayList<tListEntry>();
        updatesPos = new ArrayList<tListEntry>();
        hashForUpdates = new HashMap<Object, tHashSelectorEntry>();
        hashForSelectors = new HashMap<Object, tHashSelectorEntry>();

        // selectors with interval
        currentTarget = null;
        currentTargetSalvaged = false;
    }

    private void removeHashElement(Object key, tHashSelectorEntry element){
        element.timers.clear();
        element.timers = null;
        element.target = null;
        hashForSelectors.remove(key);
    }

    /** 'tick' the scheduler.
      You should NEVER call this method, unless you know what you are doing.
    */
    public void tick(float dt) {
        if( timeScale_ != 1.0f )
            dt *= timeScale_;
        
        // updates with priority < 0
        for (tListEntry e: updatesNeg) {
            if( ! e.paused ) {
            	try {
					e.impMethod.invoke(e.target, new Object[] {dt});
				} catch (Exception e1) {
					// TODO Auto-generated catch block
					e1.printStackTrace();
				}
            }
        }

        // updates with priority == 0
        for(tListEntry e: updates0) {
            if( ! e.paused ) {
                try {
					e.impMethod.invoke(e.target, new Object[]{ dt } );
				} catch (Exception e1) {
					// TODO Auto-generated catch block
					e1.printStackTrace();
				}
            }
        }
        
        // updates with priority > 0
        for (tListEntry e: updatesPos ) {
            if( ! e.paused ) {
                try {
					e.impMethod.invoke(e.target, new Object[]{ dt } );
				} catch (Exception e1) {
					// TODO Auto-generated catch block
					e1.printStackTrace();
				}
            }
        }
        
        // Iterate all over the  custome selectors
        ArrayList<tHashSelectorEntry> toBeRemoved = new ArrayList<tHashSelectorEntry>(); 
        for (tHashSelectorEntry elt: hashForSelectors.values()) {
            currentTarget = elt;
            currentTargetSalvaged = false;
            
            if( ! currentTarget.paused ) {                
                // The 'timers' ccArray may change while inside this loop.
                for( elt.timerIndex = 0; elt.timerIndex < elt.timers.size(); elt.timerIndex++) {
                    elt.currentTimer = elt.timers.get(elt.timerIndex);
                    elt.currentTimerSalvaged = false;

                    try {
						impMethod.invoke( elt.currentTimer, new Object[]{ dt });
					} catch (Exception e1) {
						// TODO Auto-generated catch block
						e1.printStackTrace();
					}
                    
                    if( elt.currentTimerSalvaged ) {
                        // The currentTimer told the remove itself. To prevent the timer from
                        // accidentally deallocating itself before finishing its step, we retained
                        // it. Now that step is done, it's safe to release it.
                        elt.currentTimer = null;
                    }
                    
                    elt.currentTimer = null;
                }			
            }
            
            // elt, at this moment, is still valid
            // so it is safe to ask this here (issue #490)
            // elt=elt->hh.next;
            
            // only delete currentTarget if no actions were scheduled during the cycle (issue #481)
            if( currentTargetSalvaged && currentTarget.timers.isEmpty()) {
            	toBeRemoved.add(elt);
            	// this.removeHashElement(elt.target, elt);
                // [self removeHashElement:currentTarget];
            }
        }
        
        for (tHashSelectorEntry e: toBeRemoved) {
        	this.removeHashElement(e.target, e);
        }
        
        currentTarget = null;
    }

    static class SchedulerTimerAlreadyScheduled extends RuntimeException {

		/**
		 * 
		 */
		private static final long serialVersionUID = 5996803998420105321L;

		public SchedulerTimerAlreadyScheduled(String reason) {
            super(reason);
        }
    }

    static class SchedulerTimerNotFound extends RuntimeException {
        /**
		 * 
		 */
		private static final long serialVersionUID = -1912889437889458701L;

		public SchedulerTimerNotFound(String reason) {
            super(reason);
        }
    }

    /** The scheduled method will be called every 'interval' seconds.
      If paused is YES, then it won't be called until it is resumed.
      If 'interval' is 0, it will be called every frame, but if so, it recommened to use 'scheduleUpdateForTarget:' instead.

      @since v0.99.3
    */
    public void schedule(String selector, Object target, float interval, boolean paused) {
        assert selector != null: "Argument selector must be non-nil";
        assert target != null: "Argument target must be non-nil";	

        tHashSelectorEntry element = hashForSelectors.get(target);

        if( element == null ) {
            element = new tHashSelectorEntry();
            element.target = target;
            hashForSelectors.put(target, element);
            // Is this the 1st element ? Then set the pause level to all the selectors of this target
            element.paused = paused;

        } else {
            assert element.paused == paused : "CCScheduler. Trying to schedule a selector with a pause value different than the target";
        }

        if( element.timers == null) {
            element.timers = new ArrayList<CCTimer>();
        }/* else if( element.timers.size() == element.timers )
            ccArrayDoubleCapacity(element->timers);
		*/
        CCTimer timer = new CCTimer(target, selector, interval);
        element.timers.add(timer);
    }

    /** Unshedules a selector for a given target.
     If you want to unschedule the "update", use unscheudleUpdateForTarget.
     @since v0.99.3
    */
    public void unschedule(String selector, Object target) {
        // explicity handle nil arguments when removing an object
        if( target==null && selector==null)
            return;

        assert target != null: "Target MUST not be null";
        assert selector != null: "Selector MUST not be null";

        tHashSelectorEntry element = hashForSelectors.get(target);
        if( element != null ) {
            for( int i=0; i< element.timers.size(); i++ ) {
                CCTimer timer = element.timers.get(i);

                if(selector == timer.getSelector()) {
                    if( timer == element.currentTimer && !element.currentTimerSalvaged ) {                        
                        element.currentTimerSalvaged = true;
                    }
                    	
                    element.timers.remove(i);

                    // update timerIndex in case we are in tick:, looping over the actions
                    if( element.timerIndex >= i )
                        element.timerIndex--;

                    if( element.timers.isEmpty()) {
                        if( currentTarget == element ) {
                            currentTargetSalvaged = true;						
                        } else {
                        	this.removeHashElement(element.target, element);
                        }
                    }
                    return;
                }
            }
        }

        // Not Found
        //	NSLog(@"CCScheduler#unscheduleSelector:forTarget: selector not found: %@", selString);
    }

    /** Unschedules the update selector for a given target
      @since v0.99.3
      */
    public void unscheduleUpdate(Object target) {
        if( target == null )
            return;
        hashForUpdates.remove(target);
    }

    /** Unschedules all selectors for a given target.
     This also includes the "update" selector.
     @since v0.99.3
    */
	public void unscheduleAllSelectors(Object target) {
        // TODO Auto-generated method stub
        // explicit nil handling
        if( target == null )
            return;

        // Custom Selectors
        tHashSelectorEntry element = hashForSelectors.get(target);

        if( element != null) {
            if(!element.currentTimerSalvaged ) {
                // element.currentTimer retain;
                element.currentTimerSalvaged = true;
            }
            element.timers.clear();
            // ccArrayRemoveAllObjects(element->timers);
            if( currentTarget == element )
                currentTargetSalvaged = true;
            else {
            	this.removeHashElement(element.target, element);
                // [self removeHashElement:element];
            }
        }

        // Update Selector
        this.unscheduleUpdate(target);
	}

    /** Unschedules all selectors from all targets.
      You should NEVER call this method, unless you know what you are doing.

      @since v0.99.3
      */
    public void unscheduleAllSelectors() {
        // Custom Selectors
        for(tHashSelectorEntry element : hashForSelectors.values()) {	
            Object target = element.target;
            unscheduleAllSelectors(target);
        }

        // Updates selectors        
        for (tListEntry entry:updates0) {
        	unscheduleUpdate(entry.target);
        }
        for (tListEntry entry:updatesNeg) {
        	unscheduleUpdate(entry.target);
        }
        for (tListEntry entry:updatesPos) {
        	unscheduleUpdate(entry.target);
        }
    }

    /** Resumes the target.
     The 'target' will be unpaused, so all schedule selectors/update will be 'ticked' again.
     If the target is not present, nothing happens.
     @since v0.99.3
    */
	public void resume(Object target) {
        assert  target != null: "target must be non nil";

        // Custom Selectors
        tHashSelectorEntry element = hashForSelectors.get(target);
        if( element != null )
            element.paused = false;

        // Update selector
        tHashSelectorEntry elementUpdate = hashForUpdates.get(target);
        if( elementUpdate != null) {
            assert elementUpdate.target != null: "resumeTarget: unknown error";
            elementUpdate.paused = false;
        }	

	}

    /** Pauses the target.
     All scheduled selectors/update for a given target won't be 'ticked' until the target is resumed.
     If the target is not present, nothing happens.
     @since v0.99.3
    */
	public void pause(Object target) {
        assert target != null: "target must be non nil";

        // Custom selectors
        tHashSelectorEntry element = hashForSelectors.get(target);
        if( element != null )
            element.paused = true;

        // Update selector
        tHashSelectorEntry elementUpdate = hashForUpdates.get(target);
        if( elementUpdate != null) {
            assert elementUpdate.target != null:"pauseTarget: unknown error";
            elementUpdate.paused = true;
        }

    }

    /** Schedules the 'update' selector for a given target with a given priority.
      The 'update' selector will be called every frame.
      The lower the priority, the earlier it is called.
      @since v0.99.3
    */
	public void scheduleUpdate(Object target, int priority, boolean paused) {
        // TODO Auto-generated method stub
        if (ccConfig.COCOS2D_DEBUG >= 1) {
            tHashSelectorEntry hashElement = hashForUpdates.get(target);
            assert hashElement == null:"CCScheduler: You can't re-schedule an 'update' selector'. Unschedule it first";
        }

        // most of the updates are going to be 0, that's way there
        // is an special list for updates with priority 0
        if( priority == 0 ) {
        	this.append(updates0, target, paused);
        } else if( priority < 0 ) {
        	this.priority(updatesNeg, target, priority, paused);
        } else { // priority > 0
        	this.priority(updatesPos, target, priority, paused);
        }
	}

    /** schedules a Timer.
     It will be fired in every frame.
     
     @deprecated Use scheduleSelector:forTarget:interval:paused instead. Will be removed in 1.0
    */
    public void scheduleTimer(CCTimer timer) {
        assert false: "Not implemented. Use scheduleSelector:forTarget:";
    }

    /** unschedules an already scheduled Timer
     
     @deprecated Use unscheduleSelector:forTarget. Will be removed in v1.0
     */
    public void unscheduleTimer(CCTimer timer) {
	    assert false: "Not implemented. Use unscheduleSelector:forTarget:";
    }

    /** unschedule all timers.
     You should NEVER call this method, unless you know what you are doing.
     
     @deprecated Use scheduleAllSelectors instead. Will be removed in 1.0
     @since v0.8
     */
    public void unscheduleAllTimers() {
	    assert false:"Not implemented. Use unscheduleAllSelectors";
    }

    @Override
    public void finalize () {
        unscheduleAllSelectors();
        _sharedScheduler = null;
    }

    public void append(ArrayList<tListEntry> list, Object target, boolean paused) {
        tListEntry listElement = new tListEntry();

        listElement.target = target;
        listElement.paused = paused;
        try {
			listElement.impMethod = target.getClass().getMethod(updateSelector, new Class[]{Float.TYPE});
		} catch (Exception e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

        list.add(listElement);

        // update hash entry for quicker access
        tHashSelectorEntry hashElement = new tHashSelectorEntry();
        hashElement.target = target;
        hashForUpdates.put(target, hashElement);
    }

    public void priority(ArrayList<tListEntry> list, Object target, int priority, boolean paused) {
        tListEntry listElement = new tListEntry();

        listElement.target = target;
        listElement.priority = priority;
        listElement.paused = paused;
        try {
			listElement.impMethod = target.getClass().getMethod(updateSelector, new Class[]{Float.TYPE});
		} catch (Exception e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

        list.add(listElement);

        // update hash entry for quicker access
        tHashSelectorEntry hashElement = new tHashSelectorEntry();
        hashElement.target = target;
        hashForUpdates.put(target, hashElement);
    }


}
