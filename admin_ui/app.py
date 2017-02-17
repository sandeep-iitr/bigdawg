# To run:
# export FLASK_APP=app.py
# flask run --host=0.0.0.0

from flask import Flask, redirect, render_template, request, url_for
from DockerClient import DockerClient
from CatalogClient import CatalogClient
app = Flask(__name__)


# Cluster Status
@app.route('/')
def status():
    containers = DockerClient().list_containers()
    return render_template('status.html', containers=containers)


# Data Catalog
@app.route('/catalog')
def catalog():
    objects = CatalogClient().get_objects()
    # Template expects results like:
    # objects = [
    #     (0, 'mimic2v26.a_chartdurations', 'subject_id,icustay_id,itemid,elemid,starttime,startrealtime,endtime,cuid,duration', 2, 3), 
    #     (1, 'mimic2v26.a_iodurations', 'subject_id,icustay_id,itemid,elemid,starttime,startrealtime,endtime,cuid,duration', 2, 3), 
    #     (2, 'mimic2v26.a_meddurations', 'subject_id,icustay_id,itemid,elemid,starttime,startrealtime,endtime,cuid,duration', 2, 3), 
    #     (3, 'mimic2v26.additives', 'subject_id,icustay_id,itemid,ioitemid,charttime,elemid,cgid,cuid,amount,doseunits,route', 2, 3), 
    #     (4, 'mimic2v26.admissions', 'hadm_id,subject_id,admit_dt,disch_dt', 2, 2), 
    #     (5, 'mimic2v26.censusevents', 'census_id,subject_id,intime,outtime,careunit,destcareunit,dischstatus,los,icustay_id', 2, 2), 
    #     (6, 'mimic2v26.chartevents', 'subject_id,icustay_id,itemid,charttime,elemid,realtime,cgid,cuid,value1,value1num,value1uom,value2,value2num,value2uom,resultstatus,stopped', 2, 3), 
    #     (7, 'mimic2v26.comorbidity_scores', 'subject_id,hadm_id,category,congestive_heart_failure,cardiac_arrhythmias,valvular_disease,pulmonary_circulation,peripheral_vascular,hypertension,paralysis,other_neurological,chronic_pulmonary,diabetes_uncomplicated,diabetes_complicated,hypothyroidism,renal_failure,liver_disease,peptic_ulcer,aids,lymphoma,metastatic_cancer,solid_tumor,rheumatoid_arthritis,coagulopathy,obesity,weight_loss,fluid_electrolyte,blood_loss_anemia,deficiency_anemias,alcohol_abuse,drug_abuse,psychoses,depression', 2, 3), 
    #     (8, 'mimic2v26.d_caregivers', 'cgid,label', 2, 2), 
    #     (9, 'mimic2v26.d_careunits', 'cuid,label', 2, 2), 
    #     (10, 'mimic2v26.d_chartitems', 'itemid,label,category,description', 2, 2), 
    # ]
    engines = CatalogClient().get_engines()
    return render_template('catalog.html', objects=objects, engines=engines)


# Important Links
@app.route('/links')
def links():
    links = [
        {
            "name": "Documentation",
            "href": "http://bigdawg-documentation.readthedocs.io/en/latest/index.html"
        },
        {
            "name": "Code",
            "href": "https://bitbucket.org/aelmore/bigdawgmiddle"
        },
        {
            "name": "Installation and Setup",
            "href": "http://bigdawg-documentation.readthedocs.io/en/latest/getting-started.html"
        },
    ]
    return render_template('links.html', links=links)


# Start container
@app.route('/startContainer', methods=["POST"])
def startContainer():
    action = request.form['action']
    container_name = request.form['container']
    DockerClient().start_container(container_name)
    return redirect(url_for('status'))


# Stop container
@app.route('/stopContainer', methods=["POST"])
def stopContainer():
    action = request.form['action']
    container_name = request.form['container']
    DockerClient().stop_container(container_name)
    return redirect(url_for('status'))

if __name__ == '__main__':
    # Use this line to run the server visible to external connections.
    # app.run(host='0.0.0.0')
    app.run