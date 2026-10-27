/**
 * Real FNOL photo sets: every side of one damaged vehicle plus a close-up and the VIN label, from the
 * public-domain NHTSA crash-test database (see demo-scenarios/sample-images/CREDITS.md). The files are
 * served from demo-scenarios/sample-images (Vite publicDir). Loading a set fills the claim form as a
 * claimant would; the AI then analyses the actual photos (with Gemini enabled).
 */
export type SampleSet = {
  folder: string;
  label: string;
  description: string;
  claimedAmount: string;
};

/** File name of each slot's photo in a set folder. */
export const SET_FILES: Record<string, string> = {
  FRONT: 'front.jpg',
  REAR: 'rear.jpg',
  LEFT: 'left.jpg',
  RIGHT: 'right.jpg',
  CLOSE_UP: 'close-up.jpg',
  ODOMETER_VIN: 'vin-label.jpg',
};

export const sampleSets: SampleSet[] = [
  {
    folder: 'fnol-set-corolla-side-impact',
    label: '2020 Toyota Corolla — hit on the driver side',
    description: 'Another car ran the red light and hit my car on the driver side at the intersection. Both left doors '
      + 'are pushed in, the side airbags went off and the driver door will not open.',
    claimedAmount: '9500',
  },
  {
    folder: 'fnol-set-camry-frontal',
    label: '2018 Toyota Camry — front-end collision',
    description: 'Traffic stopped suddenly on the highway and I could not brake in time. I hit the back of a stopped '
      + 'truck head-on; the bonnet is crushed, the front airbags deployed and the car is not drivable.',
    claimedAmount: '14000',
  },
  {
    folder: 'fnol-set-civic-rear-impact',
    label: '2017 Honda Civic — rear-ended',
    description: 'I was stopped at a red light when a van hit me from behind at speed. The boot and rear bumper are '
      + 'pushed in to the back seats and the boot will not open.',
    claimedAmount: '12000',
  },
  {
    folder: 'fnol-set-equinox-side-pole',
    label: '2018 Chevrolet Equinox — slid into a pole',
    description: 'The car slid on a wet road and hit a utility pole with the driver side, just behind the front wheel. '
      + 'The driver door is caved in and the curtain airbag deployed.',
    claimedAmount: '11000',
  },
  {
    folder: 'fnol-set-altima-offset-frontal',
    label: '2017 Nissan Altima — oncoming car hit the front-left corner',
    description: 'An oncoming car crossed the centre line and hit the front-left corner of my car. The front left wheel '
      + 'is pushed back, the bonnet is folded and the airbags deployed.',
    claimedAmount: '25000',
  },
  {
    folder: 'fnol-set-f150-frontal',
    label: '2018 Ford F-150 pickup — hit a concrete barrier',
    description: 'I lost control on the highway and drove straight into a concrete barrier. The whole front end is '
      + 'crushed, the radiator is leaking and the airbags deployed.',
    claimedAmount: '18000',
  },
];
