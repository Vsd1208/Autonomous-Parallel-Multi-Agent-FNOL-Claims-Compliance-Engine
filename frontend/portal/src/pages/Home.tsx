import React from 'react';
import { Link } from 'react-router-dom';

export default function Home() {
  return (
    <>
      <section className="p-hero">
        <h1>Had an accident or damage at home?</h1>
        <p>Report it here at any time. It takes about 10 minutes, and you will get a claim number straight away.</p>
        <div className="p-hero-actions">
          <Link to="/report" className="p-btn p-btn-primary p-btn-lg">Report a claim</Link>
          <Link to="/track" className="p-btn p-btn-secondary p-btn-lg">Track a claim</Link>
        </div>
      </section>

      <section className="p-tiles" aria-label="Before you start">
        <div className="p-tile">
          <h2>What you need</h2>
          <ul>
            <li>Your policy number</li>
            <li>When and where it happened</li>
            <li>Photos of the damage: for a vehicle, every side as well as the damage itself</li>
            <li>If someone else was involved: their plate, insurer and any police report number</li>
          </ul>
        </div>
        <div className="p-tile">
          <h2>What happens next</h2>
          <ol>
            <li>You get a claim number and a written acknowledgement.</li>
            <li>A licensed claims adjuster reviews your claim and photos.</li>
            <li>If anything is missing, we send you a secure link to add it.</li>
            <li>We tell you the decision in writing, within the time your state requires.</li>
          </ol>
        </div>
        <div className="p-tile">
          <h2>How we use your photos</h2>
          <p>Automated tools help us review photos faster. Faces and number plates are blurred first, and a licensed
            adjuster makes every decision on your claim.</p>
        </div>
      </section>

      <section className="p-callout">
        <strong>Is anyone hurt, or is the vehicle in a dangerous place?</strong> Call the emergency services first. You
        can report the claim afterwards.
      </section>
    </>
  );
}
