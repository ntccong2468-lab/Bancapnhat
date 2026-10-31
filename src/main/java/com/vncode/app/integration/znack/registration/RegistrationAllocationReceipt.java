package com.vncode.app.integration.znack.registration;

import com.vncode.app.integration.znack.registration.v3.RegistrationCommandJournal;
import com.vncode.app.integration.znack.registration.v3.RegistrationJob;

/** In-memory handoff of the exact journal command to the durable native checkpoint. */
record RegistrationAllocationReceipt(String gtin,RegistrationJob.Owner owner,RegistrationCommandJournal.Command command,RegistrationIntent intent) {}
