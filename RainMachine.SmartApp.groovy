/**
 *	RainMachine Service Manager SmartApp
 * 
 *  Author: Jason Mok
 *  Date: 2014-12-20
 *
 ***************************
 *
 *  Copyright 2014 Jason Mok
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License. You may obtain a copy of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *  on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 *  for the specific language governing permissions and limitations under the License.
 *
 **************************
 *
 * REQUIREMENTS
 * 1) This only works for firmware version 3.63 on RainMachine
 * 2) You know your external IP address
 * 3) You have forwarded port 80 (Currently does not work with SSL 443/18443, this is smartthings limitation). 
 * 4) You must have all scripts installed 
 *
 **************************
 * 
 * USAGE
 * 1) Put this in SmartApp. Don't install until you have all other device types scripts added
 * 2) Configure the first page which collects your ip address & port and password to log in to RainMachine
 * 3) For each items you pick on the Programs/Zones page, it will create a device
 * 4) Enjoy!
 *
 */
definition(
	name: "RainMachine",
	namespace: "copy-ninja",
	author: "Jason Mok",
	description: "Connect your RainMachine to control your irrigation",
	category: "SmartThings Labs",
	iconUrl:   "http://smartthings.copyninja.net/icons/RainMachine@1x.png",
	iconX2Url: "http://smartthings.copyninja.net/icons/RainMachine@2x.png",
	iconX3Url: "http://smartthings.copyninja.net/icons/RainMachine@3x.png"
)

preferences {
	page(name: "prefLogIn", title: "RainMachine")    
	page(name: "prefListProgramsZones", title: "RainMachine")
}

/* Preferences */
def prefLogIn() {
	def showUninstall = ip_address != null && password != null && ip_port != null
	return dynamicPage(name: "prefLogIn", title: "Connect to RainMachine", nextPage:"prefListProgramsZones", uninstall:showUninstall, install: false) {
		section("Server Information"){
			input("ip_address", "text", title: "IP Address/Host Name", description: "IP Address/Host Name of RainMachine")
			input("ip_port", "text", title: "Port Number", description: "Forwarded port RainMachine")			
		}
		section("Login Credentials"){
			input("password", "password", title: "Password", description: "RainMachine password")
		}
		section("Connectivity"){
			input(name: "polling", title: "Server Polling", type: "number", description: "in seconds", defaultValue: "300", required:false )
		}              
	}
}

def prefListProgramsZones() {
	if (forceLogin()) {
		return dynamicPage(name: "prefListProgramsZones",  title: "Programs/Zones", install:true, uninstall:true) {
			section("Select which programs to use"){
				input(name: "programs", type: "enum", required:false, multiple:true, metadata:[values:getProgramList()])
			}
			section("Select which zones to use"){
				input(name: "zones", type: "enum", required:false, multiple:true, metadata:[values:getZoneList()])
			}
		}  
	}
}

/* Initialization */
def installed() {
	forceLogin()
	initialize()
}

def updated() {
	unsubscribe()
	login()
	initialize()
}

def uninstalled() {
	def delete = getAllChildDevices()
	delete.each { deleteChildDevice(it.deviceNetworkId) }
}	

def initialize() {    
	// Set initial polling run
	state.polling = [ 
		last: now(),
		runNow: true
	]
    
	// Get initial device status in state.data
	refresh()
	
	def progZones = []
	def programList = [:] 
	def zoneList = [:]
	def delete 
    
	// Collect programs and zones 
	if (settings.programs) {
		if (settings.programs[0].size() > 1) {
			progZones = settings.programs
		} else {
			progZones.add(settings.programs)
		}
		programList = getProgramList()
	}
	if (settings.zones) {
		if (settings.zones[0].size() > 1) {
			settings.zones.each { dni -> progZones.add(dni)}
		} else {
			progZones.add(settings.zones)
		}
		zoneList = getZoneList()
	}
    
	// Create device if selected and doesn't exist
	progZones.each { dni ->    	
		def childDevice = getChildDevice(dni)
		def childDeviceAttrib = [:]
		if (!childDevice) {
			if (dni.contains("prog")) {
				childDeviceAttrib = ["name": "RainMachine Program: " + zoneList[dni], "label": "RainMachine Program: " + zoneList[dni], "completedSetup": true]
			} else if (dni.contains("zone")) {
				childDeviceAttrib = ["name": "RainMachine Zone: " + zoneList[dni], "label": "RainMachine Zone: " + zoneList[dni], "completedSetup": true]
			}
			addChildDevice("copy-ninja", "RainMachine", dni, location.hubs[0].id, childDeviceAttrib)
		}         
	}
    
	// Delete child devices that are not selected in the settings
	if (!progZones) {
		delete = getAllChildDevices()
	} else {
		delete = getChildDevices().findAll { 
			!progZones.contains(it.deviceNetworkId) 
		}
	}
	delete.each { deleteChildDevice(it.deviceNetworkId) }
}

/* Access Management */
private forceLogin() {
	//Reset token and expiry
	state.auth = [ 
		expires_in: now() - 500,
		access_token: "" 
	]
    state.data = [:]
	return doLogin()
}

private login() {
	if (!(state.auth.expires_in > now())) {
		return doLogin()
	} else {
		return true
	}
}

private doLogin() { 
	// TODO: make call through hub later... 
	apiPost("/api/4/auth/login",[pwd: settings.password, remember: 1]) { response ->
		if (response.status == 200) {
			state.auth.expires_in = now() + response.data.expires_in		
			state.auth.access_token = response.data.access_token
			return true
		} else {
			return false
		}
	} 
}

// Listing all the programs you have in RainMachine
private getProgramList() { 	    
	def programsList = [:]
	apiGet("/api/4/program") { response ->
		if (response.status == 200) {
			response.data.programs.each { program ->
				if (program.uid) {
					def dni = [ app.id, "prog", program.uid ].join('|')
					def endTime = 0 //TODO: calculate time left for the program                   
					programsList[dni] = program.name
					state.data[dni] = [
						status: program.status,
						endTime: endTime
					]
				}
			}
		}
	}    
	return programsList
}

// Listing all the zones you have in RainMachine
private getZoneList() {
	def zonesList = [:]
	apiGet("/api/4/zone") { response ->
		if (response.status == 200) {
			response.data.zones.each { zone ->
                    		def dni = [ app.id, "zone", zone.uid ].join('|')
				def endTime = now + ((zone.remaining?:0) * 1000)
				zonesList[dni] = zone.name
				state.data[dni] = [
					status: zone.state,
					endTime: endTime
				]
			}
		}
	}    
    	return zonesList
}

// Updates data for
private updateDeviceData() {    
	// automatically checks if the token has expired, if so login again
    	if (login()) {        
		// Next polling time, defined in settings
		def now = now()
		def next = (state.polling.last?:0) + ((settings.polling?:600) * 1000)
		if ((now > next) || (state.polling.runNow)) {
			// set polling states
			state.polling.last = now
			state.polling.runNow = false
			
			// Get all the program information
			getProgramList()
			
			// Get all the program information
			getZoneList()
		}
    	}
}

// Returns UID of a Zone or Program
private getChildUID(child) {
	return child.device.deviceNetworkId.split("\\|")[2]
}

// Returns Type of a Zone or Program
private getChildType(child) {
	def childType = child.device.deviceNetworkId.split("\\|")[1]
	if (childType == "prog") { return "program" }
	if (childType == "zone") { return "zone" }
}

/* api connection */
// HTTP GET call
private apiGet(apiPath, callback = {}) {
	def apiParams = [
		uri: "http://" + settings.ip_address + ":" + settings.ip_port,
		path: apiPath,
		contentType: "application/json",
		query: [ access_token: state.auth.access_token ]
	]
    
    	try {
		httpGet(apiParams) { response ->
			if (response.data.ErrorMessage) {
				log.debug "API Error: $response.data"
			}            
			callback(response)
		}
	}
    	catch (Error e)	{
		log.debug "API Error: $e"
	}
}
// HTTP POST call
def apiPost(apiPath, apiBody, callback = {}) {
	try {
		httpPostJson("http://" + settings.ip_address + ":" + settings.ip_port + apiPath + "?access_token=" + state.auth.access_token, apiBody) { response ->
			if (response.data.ErrorMessage) {
				log.debug "API Error: $response.data"
			}            
			callback(response)
		}
	}
	catch (Error e)	{
		log.debug "API Error: $e"
	}
}

/* for SmartDevice to call */
// Refresh data
def refresh() {
	log.info "refresh()"
	state.polling.runNow = true
	state.data = [:]
	updateDeviceData()
}

// Get single device status
def getDeviceStatus(child) {
	log.info "getDeviceStatus()"
	//tries to get latest data if polling limitation allows
	updateDeviceData()
	if (state.data[child.device.deviceNetworkId]) {
		switch (state.data[child.device.deviceNetworkId].status) {
			case 0  : return "inactive"
			case 1  : return "active"
			case 2  : return "pending"
			default : return "inactive"
		}
	} else {
	    return "inactive"
	}
}
// Get single device ending time
def getDeviceEndTime(child) {
	//tries to get latest data if polling limitation allows
	updateDeviceData()
	if (state.data[child.device.deviceNetworkId]) {
		return state.data[child.device.deviceNetworkId].endTime
	}
}

	// Send command to start or stop
	def sendCommand(child, apiCommand, apiTime, apiType) {
	def childUID = getChildUID(child)
	def childType = getChildType(child)
	def commandSuccess = false
	def zonesActive = false
	def apiPath = "/api/4/" + childType + "/" + childUID + "/" + apiCommand
	def apiBody = []
    
	//Try to get the latest data first
	updateDeviceData()    
	
	//Checks for any active running sprinklers before allowing another program to run
	if (childType == "program") {
		apiBody = [pid: childUID]
		if (apiCommand == "start") { 
			state.data.each { dni, data -> if ((data.status == 1) || (data.status == 2)) { zonesActive = true }}
			if (!zonesActive) {
				apiPost(apiPath, apiBody) 
				commandSuccess = true
			} else {
				commandSuccess = false
			}        
		} else {
			apiPost(apiPath, [pid: childUID]) 
			commandSuccess = true
		}
	} 
	
	//Zones will require time
	if (childType == "zone") {
		apiBody = [time: apiTime]
		apiPost(apiPath, apiBody)
		commandSuccess = true
	}  
    
	//Forcefully get the latest data after waiting for 2 seconds
	pause(2000)
	refresh()
	
	return commandSuccess
}

//Stop everything
def sendStopAll() {
	def apiPath = "/api/4/watering/stopall"
	def apiBody = [all: "true"]
	apiPost(apiPath, apiBody)
	
	//Forcefully get the latest data after waiting for 2 seconds
	pause(2000)
	refresh()
	return true
}
