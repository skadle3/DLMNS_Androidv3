package com.gt.seniordesign.dlmns;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;

public class ModifyTagMain extends Activity{

	private KnownDevice selectedDevice;
	private EditText text_tag;
	private String new_tag_name;
	private String new_dutycycle_str;
	private int new_duty_cycle;
	private int position;

	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		getActionBar().setTitle("Modify Tag Information");
		setContentView(R.layout.activity_modify);	

		//setup OnClickListeners    
		Button modify_pressed = (Button) findViewById(R.id.modify_id_modify);
		Button delete_pressed = (Button) findViewById(R.id.modify_id_delete);

		// set the display of the duty cycle and tag name to current tag's	    
		TextView text_name = (TextView) findViewById(R.id.editText2);

		// Get the Intent
		Intent currentIntent = getIntent();
		position = currentIntent.getIntExtra("position", 0);
		selectedDevice = MainActivity.monitoredDevicesAdapter.getDevice(position);

		text_name.setText(selectedDevice.getName());

		Spinner spinnerDuty = (Spinner) findViewById(R.id.spinner1);
		ArrayAdapter<CharSequence> adapter =  ArrayAdapter.createFromResource(this,
				R.array.duty_cycle_array, android.R.layout.simple_spinner_item);
		spinnerDuty.setAdapter(adapter);
		spinnerDuty.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
			public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
				Spinner spinner = (Spinner) findViewById(R.id.spinner1);
				new_dutycycle_str = spinner.getAdapter().getItem(pos).toString();
			}
			public void onNothingSelected(AdapterView<?> parent) {
				new_dutycycle_str = "32";
			}
		});

		int initialIndex = 0;
		switch (selectedDevice.getDutyCycle()) {
			case 32:
				initialIndex = 0;
				break;
			case 64:
				initialIndex = 1;
				break;
			case 128:
				initialIndex = 2;
				break;
			case 256:
				initialIndex = 3;
				break;
		}
		spinnerDuty.setSelection(initialIndex);

		modify_pressed.setOnClickListener(new View.OnClickListener() {

			@Override
			public void onClick(View v) {
				// do this if SAVE gets pressed
				//editText2 is from new tag input field
				text_tag = (EditText)findViewById(R.id.editText2);
				new_tag_name = text_tag.getText().toString();
				//editText1 is from new dutycycle input field
				//text_duty = (EditText)findViewById(R.id.editText1);

				//check if strings are NULL before proceeding
				if(new_tag_name == null || new_tag_name.length() <= 0){
					new_tag_name = selectedDevice.getName();
				}

				new_duty_cycle = Integer.parseInt(new_dutycycle_str);

				selectedDevice.setName(new_tag_name);
				selectedDevice.new_duty_cycle = new_duty_cycle;
				MainActivity.monitoredDevicesAdapter.notifyDataSetChanged();
				MainActivity.updateStoredTags();
				finish();
			}
		});


		delete_pressed.setOnClickListener(new View.OnClickListener() {

			@Override
			public void onClick(View v) {
				DLMMonitorService.monitoredDevices.remove(position);
				finish();				
			}
		});

	}

}
